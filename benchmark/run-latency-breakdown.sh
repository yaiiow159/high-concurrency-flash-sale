#!/usr/bin/env bash
# 延遲歸因：前面的實驗排除了三個嫌疑（背景負載 +2%、JIT +13%、執行緒 +2%），
# 而 CPU 只用掉 9/20 核。也就是說請求不是在算，是在等。
#
# 這一輪回答「等在哪裡」：
#   seckill_attempt_duration  應用內部（Redis Lua + Kafka 確認）花的時間
#   autocannon 觀測到的延遲    包含連線排隊與 HTTP 層
#   兩者的差 = 請求進到 attempt() 之前排隊的時間
#
# 開跑前先排空 Kafka 積壓：消費端還在追前一輪的訊息時，MySQL 與 Kafka
# 都在滿載工作，量到的會是「壓測 + 追積壓」的混合，不是熱路徑本身。
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
  --server.tomcat.threads.max=900
)

total_lag() {
  docker compose -f ../docker-compose.yml exec -T kafka bash -c \
    '/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group seckill-order-creator 2>/dev/null' \
    2>/dev/null | awk '$6 ~ /^[0-9]+$/ { s += $6 } END { print s+0 }'
}

echo "=== 排空 Kafka 積壓 ==="
for _ in $(seq 1 40); do
  lag=$(total_lag)
  echo "  剩餘積壓 ${lag}"
  [ "${lag:-1}" -lt 500 ] && break
  sleep 20
done

echo
echo "=== 重啟應用（完整 JIT、執行緒 900、acks=all 生產設定）==="
powershell -NoProfile -Command "
  Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" |
    Where-Object { \$_.CommandLine -match 'flash-sale|FlashSaleApplication' } |
    ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1
sleep 3
nohup java -jar "$JAR" --spring.profiles.active=dev "${LIMITS[@]}" >"$LOG" 2>&1 &
for _ in $(seq 1 90); do
  curl -sf -m 2 localhost:8080/actuator/health >/dev/null 2>&1 && { echo "  已就緒"; break; }
  sleep 2
done
sleep 3

docker compose -f ../docker-compose.yml stop elasticsearch minio grafana prometheus tempo >/dev/null 2>&1
sleep 4

node users.mjs >/dev/null
curl -s -m 10 localhost:8080/actuator/prometheus > metrics-before.txt

node capacity-probe.mjs "$ACTIVITY" "$CONNS" "$SECS" F

curl -s -m 10 localhost:8080/actuator/prometheus > metrics-after.txt
node analyse-latency.mjs

docker compose -f ../docker-compose.yml start elasticsearch minio grafana prometheus tempo >/dev/null 2>&1
echo "完成。"
