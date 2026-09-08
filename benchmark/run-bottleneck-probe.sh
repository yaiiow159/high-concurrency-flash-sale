#!/usr/bin/env bash
# 瓶頸定位：情境 A–C 顯示 CPU 只用掉 9/20 核，機器沒滿載，
# 但 TPS 停在 1,800、延遲隨併發線性上升。那就不是算力問題。
#
# 已知：熱路徑的 Kafka 投遞是 send().get(500ms)，請求執行緒同步等 broker 確認。
# Java 每請求只花 3.7ms CPU，卻佔住執行緒約 217ms——其餘都在等。
#
# 兩個假設，各用一次實驗分辨：
#   D  執行緒不夠：把 Tomcat 執行緒從 400 拉到 900
#   E  下游是 Kafka：拿掉 acks=all 的等待成本（acks=1），其餘不變
#
# 若 D 有效 → 瓶頸是「執行緒被阻塞佔滿」；若 E 有效 → 瓶頸是 broker 確認延遲。
set -uo pipefail
cd "$(dirname "$0")"

JAR=../flash-sale-api/target/flash-sale.jar
LOG=/tmp/flash-sale-probe.log
ACTIVITY=9001
CONNS=${CONNS:-500}
SECS=${SECS:-15}

LIMITS=(
  --flash-sale.rate-limit.capacity=100000000
  --flash-sale.rate-limit.refill-per-second=100000000
  --resilience4j.ratelimiter.instances.seckill.limit-for-period=100000000
  --flash-sale.risk.require-qualification=false
)

stop_app() {
  powershell -NoProfile -Command "
    Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" |
      Where-Object { \$_.CommandLine -match 'flash-sale|FlashSaleApplication' } |
      ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1
  sleep 3
}

start_app() {
  echo "  啟動應用：$*"
  nohup java -jar "$JAR" --spring.profiles.active=dev "${LIMITS[@]}" "$@" >"$LOG" 2>&1 &
  for _ in $(seq 1 90); do
    if curl -sf -m 2 localhost:8080/actuator/health >/dev/null 2>&1; then echo "  已就緒"; sleep 3; return 0; fi
    sleep 2
  done
  echo "  !! 啟動逾時"; tail -15 "$LOG"; return 1
}

lean_containers() {
  docker compose -f ../docker-compose.yml stop elasticsearch minio grafana prometheus tempo >/dev/null 2>&1
}
restore_containers() {
  docker compose -f ../docker-compose.yml start elasticsearch minio grafana prometheus tempo >/dev/null 2>&1
  sleep 12
}

echo "=== 情境 D：執行緒 400 → 900（其餘同 C）==="
restore_containers
stop_app; start_app --server.tomcat.threads.max=900 || exit 1
lean_containers; sleep 4
# access token 只有 15 分鐘，每一輪都重新登入，否則量到的是 401 的拒絕成本
node users.mjs >/dev/null
node capacity-probe.mjs "$ACTIVITY" "$CONNS" "$SECS" D

echo
echo "=== 情境 E：acks=all → acks=1（其餘同 D）==="
echo "  註：僅為定位瓶頸，acks=1 會犧牲 broker 故障切換時的訊息保證，不可用於正式環境"
restore_containers
stop_app; start_app --server.tomcat.threads.max=900 --spring.kafka.producer.acks=1 || exit 1
lean_containers; sleep 4
node users.mjs >/dev/null
node capacity-probe.mjs "$ACTIVITY" "$CONNS" "$SECS" E

echo
echo "=== 還原 ==="
restore_containers
echo "完成：probe-D.json / probe-E.json"
