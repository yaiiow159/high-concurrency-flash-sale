---
name: seckill-load-test
description: 秒殺系統的壓測與容量規劃流程。當要執行壓力測試、驗證吞吐上限、調校連線池與執行緒池參數、判讀效能瓶頸、或設定限流閾值時使用。涵蓋壓測腳本、指標判讀順序與瓶頸定位方法。
---

# 壓測與容量規劃

壓測的目的**不是量出一個漂亮的 QPS 數字**，而是回答三個問題：

1. 瓶頸在哪裡？
2. 超過容量時，系統是優雅降級還是崩潰？
3. `resilience4j.ratelimiter.limit-for-period` 該設多少？

第三個問題最實際：這個值設錯，限流器要嘛形同虛設，要嘛在正常流量下就開始誤殺。

---

## 前置準備

```bash
docker compose up -d
mvn -q clean package -DskipTests
java -jar flash-sale-api/target/flash-sale.jar
```

確認庫存已預熱（否則量到的全是 `STOCK_NOT_INITIALIZED` 的快速失敗，數字會很漂亮但毫無意義）：

```bash
curl "http://localhost:8080/api/v1/activities/1001"
```

壓測前把庫存放大，避免幾秒內就賣完：

```bash
curl -X POST "http://localhost:8080/api/v1/activities/1001/warm-up?force=true"
docker exec -it flash-sale-redis redis-cli SET "seckill:{a1001}:stock" 10000000
```

---

## 壓測腳本

`k6`（每個 VU 用不同 userId 與 requestId，否則會全部撞到限流與冪等）：

```javascript
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 200 },   // 爬坡
    { duration: '2m',  target: 200 },   // 穩態，這段的數字才有意義
    { duration: '30s', target: 1000 },  // 尖峰衝擊
    { duration: '1m',  target: 1000 },
    { duration: '30s', target: 0 },
  ],
};

export default function () {
  const userId = __VU * 100000 + __ITER;
  const res = http.post('http://localhost:8080/api/v1/seckill/orders',
    JSON.stringify({ activityId: 1001, quantity: 1, requestId: `k6-${userId}` }),
    { headers: { 'Content-Type': 'application/json', 'X-User-Id': String(userId) } });

  check(res, {
    'accepted or rejected cleanly': (r) => [202, 409, 429].includes(r.status),
    'no server error': (r) => r.status < 500,
  });
}
```

**注意**：預設的單一使用者限流是「桶容量 5、每秒補 1」。
每個 VU 用不同 userId 才不會全部撞在限流上。
若要專門壓測限流器本身，反過來讓所有 VU 共用同一個 userId。

---

## 指標判讀順序

**照這個順序看，能最快定位瓶頸。** 反過來（先看 CPU）通常會繞遠路。

### 1. 錯誤類型分佈

```promql
sum by (code) (rate(seckill_rejection_total[1m]))
```

| 主要錯誤碼 | 意義 | 下一步 |
|------------|------|--------|
| `B0005`（售罄） | 庫存真的賣完了 | 把庫存調大重壓 |
| `A0002`（限流） | 打到限流閾值 | 這是預期行為，確認閾值是否合理 |
| `C0001`（庫存服務不可用） | **Redis 已達瓶頸** | 往第 3 步 |
| `C0003`（訊息投遞失敗） | **Kafka 已達瓶頸** | 往第 4 步 |

### 2. 延遲分位數

```promql
histogram_quantile(0.99, sum(rate(seckill_attempt_duration_seconds_bucket[1m])) by (le))
```

熱路徑只有兩次 RTT，**正常 P99 應在 50ms 以內**。

- P99 上升但 P50 平穩 → 排隊現象，某個資源已飽和
- P50 也一起上升 → 全面性瓶頸（通常是 CPU 或 GC）

### 3. Redis

```bash
docker exec -it flash-sale-redis redis-cli --latency
docker exec -it flash-sale-redis redis-cli INFO commandstats | grep evalsha
docker exec -it flash-sale-redis redis-cli SLOWLOG GET 10
```

Redis 是單執行緒，**單實例的極限大約 8～10 萬 QPS**。
`SLOWLOG` 出現 Lua 腳本，代表腳本太重需要精簡。

超過單實例極限時，唯一的解法是**按 activityId 分片**——
因為 Lua 腳本要求同槽，分片邊界只能是活動。

### 4. Kafka

```promql
kafka_producer_record_send_rate
kafka_producer_request_latency_avg
```

生產端延遲上升時，先調 `linger.ms` 與 `batch-size`（攢批），
再考慮加分區。

### 5. 消費端是否跟得上

```promql
sum(rate(seckill_order_persist_total{result="created"}[1m]))
```

這個速率若持續低於搶購成功速率，**佇列正在堆積**。
堆積本身不是災難（削峰本來就允許），但要確認：

- 消費延遲是否在可接受範圍（使用者輪詢等得起嗎？）
- `payment-window`（15 分鐘）是否足夠消化積壓

提高 `flash-sale.mq.order-create-concurrency` 前，**先看 `hikaricp_connections_pending`**——
若連線已在排隊，加消費並行度只會讓情況更糟。

### 6. 這時才看 JVM

```promql
rate(jvm_gc_pause_seconds_sum[1m])
jvm_memory_used_bytes{area="heap"}
```

GC 停頓通常是**結果**而非原因。多半是某個地方在熱路徑上產生了過多臨時物件，
順著前五步找到的瓶頸走，通常會先解決掉。

---

## 決定限流閾值

`limit-for-period` 應設在「系統仍能維持穩定延遲的吞吐上限」，**而非硬體極限**。

做法：

1. 逐步加壓，記錄 P99 延遲隨吞吐變化的曲線
2. 找到**延遲開始明顯上翹的拐點**
3. 取拐點吞吐的 **80%** 作為閾值

留 20% 餘裕的理由：壓測環境沒有其他業務流量、沒有 GC 尖峰、沒有網路抖動。
把閾值設在拐點上，正式環境一定會越過去。

---

## 常見的壓測陷阱

**壓測機成為瓶頸。** 單台機器開幾千個連線，瓶頸往往在壓測端而非被測端。
確認壓測機的 CPU 與 file descriptor 上限。

**沒有暖機就開始量。** JIT 尚未編譯、連線池尚未填滿、快取全空。
前 30 秒的數字一律丟棄。

**所有 VU 用同一個 userId。** 會全部撞在單一使用者限流上，量到的是限流器的效能。

**忘記把庫存調大。** 幾秒後全部變成售罄，此後量到的是「本機標記快速失敗」的效能——
那條路徑當然很快，但它不是你想量的東西。

**只看平均值。** 平均延遲 20ms 可能意味著 99% 的請求是 5ms，1% 的請求是 1.5 秒。
使用者感受到的是後者。永遠看分位數。
