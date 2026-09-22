#!/usr/bin/env bash
# 產能歸因實驗：1,471 TPS 的上限來自設計還是環境？
#
# 三個情境用「同一個 jar、同一組請求、同一個限流設定」，只變動兩個變因：
#   A  C1-only JIT + 全部容器 + 背景的 vite     ← 原始報告的條件
#   B  C1-only JIT + 精簡容器 + 無 vite         ← 隔離「背景搶算力」
#   C  完整 JIT    + 精簡容器 + 無 vite         ← 隔離「JVM 沒有 C2 編譯器」
#
# 兩個限流器一律解除，否則量到的是限流器而不是系統：
#   flash-sale.rate-limit           單使用者 5 個令牌 / 秒補 1
#   resilience4j …… seckill         單機整體 2000/s
set -uo pipefail
cd "$(dirname "$0")"

JAR=../flash-sale-api/target/flash-sale.jar
LOG=/tmp/flash-sale-probe.log
ACTIVITY=9001
CONNS=${CONNS:-500}
SECS=${SECS:-15}

# 解除兩個限流器；其餘設定一律沿用 application.yml
LIMITS=(
  --flash-sale.rate-limit.capacity=100000000
  --flash-sale.rate-limit.refill-per-second=100000000
  --resilience4j.ratelimiter.instances.seckill.limit-for-period=100000000
  --flash-sale.risk.require-qualification=false
)

stop_app() {
  # 只殺跑 flash-sale 的 JVM，不動其他 java 行程
  powershell -NoProfile -Command "
    Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" |
      Where-Object { \$_.CommandLine -match 'flash-sale|FlashSaleApplication' } |
      ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1
  sleep 3
}

start_app() {
  local jit_flag="$1"
  echo "  啟動應用（${jit_flag:-完整 JIT}）…"
  # shellcheck disable=SC2086
  nohup java $jit_flag -jar "$JAR" --spring.profiles.active=dev "${LIMITS[@]}" >"$LOG" 2>&1 &
  for _ in $(seq 1 90); do
    if curl -sf -m 2 localhost:8080/actuator/health >/dev/null 2>&1; then echo "  已就緒"; sleep 3; return 0; fi
    sleep 2
  done
  echo "  !! 應用啟動逾時，見 $LOG"; tail -20 "$LOG"; return 1
}

probe() {
  node capacity-probe.mjs "$ACTIVITY" "$CONNS" "$SECS" "$1"
}

echo "=== 情境 A：C1-only JIT + 全部容器 + 背景 vite（原始報告條件）==="
docker compose -f ../docker-compose.yml start elasticsearch minio grafana prometheus tempo >/dev/null 2>&1
stop_app; start_app "-XX:TieredStopAtLevel=1" || exit 1
probe A

echo
echo "=== 情境 B：C1-only JIT + 精簡容器 + 無 vite ==="
# 應用啟動時要連 Elasticsearch 與 MinIO，所以先啟動再停容器；
# 秒殺熱路徑不碰這兩者，停掉之後熱路徑照常運作
stop_app; start_app "-XX:TieredStopAtLevel=1" || exit 1
echo "  停用秒殺熱路徑不需要的容器與背景行程…"
docker compose -f ../docker-compose.yml stop elasticsearch minio grafana prometheus tempo >/dev/null 2>&1
powershell -NoProfile -Command "
  Get-CimInstance Win32_Process -Filter \"Name='node.exe'\" |
    Where-Object { \$_.CommandLine -match 'vite' } |
    ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1
sleep 5
probe B

echo
echo "=== 情境 C：完整 JIT + 精簡容器 + 無 vite ==="
# 重啟需要 ES/MinIO 在線，起完再停回去
docker compose -f ../docker-compose.yml start elasticsearch minio >/dev/null 2>&1
sleep 12
stop_app; start_app "" || exit 1
docker compose -f ../docker-compose.yml stop elasticsearch minio >/dev/null 2>&1
sleep 5
probe C

echo
echo "=== 還原容器 ==="
docker compose -f ../docker-compose.yml start elasticsearch minio grafana prometheus tempo >/dev/null 2>&1
echo "完成。結果：probe-A.json / probe-B.json / probe-C.json"
