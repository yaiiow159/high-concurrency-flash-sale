#!/usr/bin/env bash
# 讀路徑重測。
#
# 原始那八組數字是接在秒殺壓測之後連續跑的，而每一輪秒殺會留下數萬筆 Kafka 訊息；
# 消費端追積壓時，11 個消費端的 21 條執行緒與 HTTP 請求搶同一個 50 條的 Hikari
# 連線池，讀路徑因此被系統性拖慢。加上 spring-boot:run 預設關掉 C2 編譯器，
# 兩者都會壓低數字。
#
# 這一輪只修正量測條件，不動任何程式：
#   1. 用 java -jar 啟動（完整 JIT）
#   2. 開跑前把 Kafka 積壓排空到零
#   3. 全部容器保持啟動——讀路徑真的會用到 Elasticsearch 與 MySQL
#
# 壓測期間同時取樣 CPU，讓「這次有沒有被別人干擾」有數字可查。
set -uo pipefail
cd "$(dirname "$0")"

JAR=../flash-sale-api/target/flash-sale.jar
LOG=/tmp/flash-sale-read.log

total_lag() {
  docker compose -f ../docker-compose.yml exec -T kafka bash -c \
    '/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups 2>/dev/null' \
    2>/dev/null | awk '$6 ~ /^[0-9]+$/ { s += $6 } END { print s+0 }'
}

# Outbox 中繼每秒撈 200 筆待發事件送 Kafka，那是一份持續的背景寫入負載。
# 只排空 Kafka 消費群組不夠：待發佇列還沒清空時，讀路徑量到的是
# 「壓測 ＋ 中繼發送 ＋ 下游消費」的混合。漏掉這一項時 MySQL 會停在 220% CPU，
# 而讀路徑本身根本沒有那麼重。
outbox_pending() {
  docker compose -f ../docker-compose.yml exec -T mysql \
    mysql -uroot -proot flash_sale -N -B \
    -e "SELECT COUNT(*) FROM outbox_event WHERE status='PENDING'" \
    2>/dev/null | tr -d '\r' | tail -1
}

echo "=== 確認容器 ==="
docker compose -f ../docker-compose.yml start elasticsearch minio grafana prometheus tempo mysql redis kafka >/dev/null 2>&1
sleep 10

echo "=== 重啟應用（完整 JIT）==="
powershell -NoProfile -Command "
  Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" |
    Where-Object { \$_.CommandLine -match 'flash-sale|FlashSaleApplication' } |
    ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1
sleep 3
nohup java -jar "$JAR" --spring.profiles.active=dev >"$LOG" 2>&1 &
for _ in $(seq 1 90); do
  curl -sf -m 2 localhost:8080/actuator/health >/dev/null 2>&1 && { echo "  已就緒"; break; }
  sleep 2
done
sleep 5

echo "=== 排空背景負載（Kafka 消費積壓 ＋ Outbox 待發）==="
for _ in $(seq 1 80); do
  lag=$(total_lag)
  pending=$(outbox_pending)
  if [ "${lag:-1}" -lt 500 ] && [ "${pending:-1}" -lt 200 ]; then
    echo "  消費積壓 ${lag} / Outbox 待發 ${pending}，可以開始"
    break
  fi
  echo "    消費積壓 ${lag} / Outbox 待發 ${pending}"
  sleep 15
done

# read-bench 會先整套暖身一遍（8 × 6s）再量測（8 × 12s）。
# 取樣器蓋住整段，暖身那一遍的 CPU 也一併記錄，反正報告只引用量測值。
SAMPLE_SECS=$(( 8 * 7 + 8 * 13 + 20 ))
node sampler.mjs sample-read.json "$SAMPLE_SECS" &
SAMPLER_PID=$!

# 不要把 stderr 丟掉：情境進度走 stderr，登入失敗之類的錯誤也走 stderr，
# 一併導到 /dev/null 的話失敗會安靜地產生一個空檔案
node read-bench.mjs > read-result.md 2> read-progress.log || {
  echo "  !! read-bench 失敗："; tail -20 read-progress.log; }
cat read-result.md

wait $SAMPLER_PID 2>/dev/null || true
echo
echo "=== 壓測期間的 CPU ==="
node -e "
const s = JSON.parse(require('fs').readFileSync('sample-read.json', 'utf8'));
console.log('  容器合計 ' + s.containerCpuTotal + '% 單核：' +
  s.containers.map(c => c.name.replace('flash-sale-', '') + ' ' + c.cpuPct + '%').join(', '));
console.log('  原生行程：' + s.processes.map(p => p.name + '(' + p.pid + ') ' + p.cores + ' 核').join(', '));
"
echo "完成：read-result.md"
