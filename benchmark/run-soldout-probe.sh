#!/usr/bin/env bash
# 售罄路徑的真實成本。
#
# 原始報告量到「已售罄 1,460 QPS」，只比有庫存的 1,244 快 17%。但售罄的請求
# 被 Caffeine 本機標記直接擋下，不碰 Redis、不碰 Kafka，理論上應該快上數倍。
# 而作廢輪次的旁證顯示，在更前面就被擋掉的請求（401／503）可以跑到 5,140–6,205 TPS，
# 代表 HTTP 層與壓測工具本身不是上限。
#
# 這一輪把兩條路徑放在同一組條件下量：
#   I  有庫存（實際扣減，走完 Redis Lua + Kafka 確認）
#   J  已售罄（本機標記短路，零遠端呼叫）
#
# 售罄狀態用一個庫存為 0 的活動製造，不去動壓測主活動 9001 的庫存。
set -uo pipefail
cd "$(dirname "$0")"

JAR=../flash-sale-api/target/flash-sale.jar
LOG=/tmp/flash-sale-probe.log
STOCKED=9001
SOLDOUT=${SOLDOUT_ACTIVITY:-9002}
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
  for _ in $(seq 1 30); do
    lag=$(total_lag)
    [ "${lag:-1}" -lt 500 ] && { echo "  積壓 ${lag}，可以開始"; return 0; }
    echo "    剩 ${lag}"
    sleep 15
  done
}

echo "=== 準備一個售罄活動（id=${SOLDOUT}）==="
docker compose -f ../docker-compose.yml exec -T mysql sh -c "mysql -uroot -proot flash_sale -e \"
  INSERT INTO seckill_activity (id, sku_id, product_name, seckill_price, total_stock, per_user_limit, start_at, end_at, status, version)
  VALUES (${SOLDOUT}, 2004, '售罄壓測用', 1.00, 0, 1000000, '2026-01-01 00:00:00', '2027-12-31 00:00:00', 'ONLINE', 0)
  ON DUPLICATE KEY UPDATE total_stock = 0, status = 'ONLINE';\"" 2>&1 | grep -v Warning || true
# Redis 餘量歸零，讓熱路徑扣減時判定售罄並種下本機標記
docker compose -f ../docker-compose.yml exec -T redis redis-cli SET "seckill:{a${SOLDOUT}}:stock" 0 >/dev/null 2>&1

echo "=== 重啟應用 ==="
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

echo "=== 情境 I：有庫存 ==="
drain
node users.mjs >/dev/null
node capacity-probe.mjs "$STOCKED" "$CONNS" "$SECS" I

echo
echo "=== 情境 J：已售罄（本機標記短路）==="
drain
node users.mjs >/dev/null
# 先打一發讓本機售罄標記種下去，否則第一批請求仍會走到 Redis
curl -s -o /dev/null -X POST localhost:8080/api/v1/seckill/orders \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $(node -e "console.log(JSON.parse(require('fs').readFileSync('tokens.json','utf8'))[0])")" \
  -d "{\"activityId\":${SOLDOUT},\"quantity\":1,\"requestId\":\"$(date +%s)-warm\"}" || true
node capacity-probe.mjs "$SOLDOUT" "$CONNS" "$SECS" J 409

docker compose -f ../docker-compose.yml start elasticsearch minio grafana prometheus tempo >/dev/null 2>&1
echo "完成：probe-I.json / probe-J.json"
