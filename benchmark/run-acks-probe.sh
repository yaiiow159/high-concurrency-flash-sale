#!/usr/bin/env bash
# 最後一步：等待發生在 attempt() 內部（97% 的時間不是 CPU），而熱路徑裡唯一會
# 等待遠端的阻塞呼叫是 kafkaTemplate.send().get()。這一輪直接對照 acks 設定。
#
#   G  acks=all（正式設定）
#   H  acks=1  （只等 leader 落地，不等副本）
#
# 兩輪之間排空積壓：消費端還在追訊息時 MySQL 與 Kafka 都在忙，
# 量到的會是「壓測 + 追積壓」的混合。
#
# 併發降到 200、每輪 12 秒：500 併發下 p90 抖動大且會觸發熔斷（F 輪 34% 是 503），
# 量測目的在比較兩組設定，不在逼出極限。
set -uo pipefail
cd "$(dirname "$0")"

JAR=../flash-sale-api/target/flash-sale.jar
LOG=/tmp/flash-sale-probe.log
ACTIVITY=9001
CONNS=200
SECS=12

LIMITS=(
  --flash-sale.rate-limit.capacity=100000000
  --flash-sale.rate-limit.refill-per-second=100000000
  --resilience4j.ratelimiter.instances.seckill.limit-for-period=100000000
  --flash-sale.risk.require-qualification=false
  --server.tomcat.threads.max=900
)

total_lag() {
  docker compose -f ../docker-compose.yml exec -T kafka bash -c \
    '/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group seckill-order-creator 2>/dev/null' \
    2>/dev/null | awk '$6 ~ /^[0-9]+$/ { s += $6 } END { print s+0 }'
}

drain() {
  echo "  排空積壓…"
  for _ in $(seq 1 30); do
    lag=$(total_lag)
    [ "${lag:-1}" -lt 500 ] && { echo "  積壓 ${lag}，可以開始"; return 0; }
    echo "    剩 ${lag}"
    sleep 15
  done
}

run_round() {
  local label="$1"; shift
  echo "=== 情境 ${label}：$* ==="
  powershell -NoProfile -Command "
    Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" |
      Where-Object { \$_.CommandLine -match 'flash-sale|FlashSaleApplication' } |
      ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1
  sleep 3
  nohup java -jar "$JAR" --spring.profiles.active=dev "${LIMITS[@]}" "$@" >"$LOG" 2>&1 &
  for _ in $(seq 1 90); do
    curl -sf -m 2 localhost:8080/actuator/health >/dev/null 2>&1 && break
    sleep 2
  done
  sleep 3
  drain
  node users.mjs >/dev/null
  curl -s -m 10 localhost:8080/actuator/prometheus > "metrics-before.txt"
  node capacity-probe.mjs "$ACTIVITY" "$CONNS" "$SECS" "$label"
  curl -s -m 10 localhost:8080/actuator/prometheus > "metrics-after.txt"
  node analyse-latency.mjs "$label"
  echo
}

docker compose -f ../docker-compose.yml stop elasticsearch minio grafana prometheus tempo >/dev/null 2>&1

run_round G --spring.kafka.producer.acks=all
run_round H --spring.kafka.producer.acks=1

docker compose -f ../docker-compose.yml start elasticsearch minio grafana prometheus tempo >/dev/null 2>&1
echo "完成：probe-G.json / probe-H.json"
