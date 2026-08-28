---
name: oversell-incident
description: 超賣、少賣或庫存對不上的事故排查手冊。當線上出現庫存數量異常、訂單數超過庫存、庫存卡住不回補、Outbox 事件堆積、或補償失敗告警時使用。提供止血優先的排查順序、診斷指令與根因定位路徑。
---

# 庫存異常事故排查

**先止血，再找根因。** 秒殺事故的特性是損失隨時間線性累積——
每多一分鐘就多賣出一批不存在的商品。定位根因可以事後做，止血不行。

---

## 第 0 步：判斷是哪一類問題

| 症狀 | 類別 | 嚴重度 |
|------|------|--------|
| 訂單數 > 活動總庫存 | **超賣** | 最高。已產生無法履約的訂單 |
| Redis 餘量 > 0 但前端顯示售罄 | 少賣（本機標記未清） | 中。損失營收，可自行恢復 |
| Redis 餘量 = 0，訂單數 < 總庫存 | 少賣（庫存被鎖住） | 高。庫存永久消失，不會自行恢復 |
| `seckill_compensation_total{result="failure"} > 0` | 補償失敗 | 高。等同上一項 |

---

## 止血手段

### 超賣：立刻下架活動

```sql
UPDATE seckill_activity SET status = 'OFFLINE' WHERE id = ?;
```

活動快取的 L1 TTL 為 5 秒、L2 為 5 分鐘，**最慢 5 分鐘後所有節點才會停止接單**。
需要更快的話同時清掉 L2 快取：

```bash
docker exec -it flash-sale-redis redis-cli DEL "seckill:cache:activity:<activityId>"
```

若連這樣都太慢，直接把庫存鍵歸零——Lua 會立刻回報售罄：

```bash
docker exec -it flash-sale-redis redis-cli SET "seckill:{a<activityId>}:stock" 0
```

### 少賣：確認是不是本機標記卡住

售罄標記 TTL 只有 3 秒，**超過幾秒仍持續售罄就不是標記問題**，
應往「庫存被鎖住」的方向查。

---

## 診斷指令

### 核對 Redis 與資料庫

```bash
# Redis 目前餘量
docker exec -it flash-sale-redis redis-cli GET "seckill:{a1001}:stock"

# 已扣減但未釋放的請求數（= 應該對應的訂單數）
docker exec -it flash-sale-redis redis-cli HLEN "seckill:{a1001}:req"

# 使用者維度的已購量（找異常大戶）
docker exec -it flash-sale-redis redis-cli HGETALL "seckill:{a1001}:user"
```

```sql
-- 訂單狀態分佈
SELECT status, COUNT(*), SUM(quantity)
FROM seckill_order WHERE activity_id = 1001 GROUP BY status;

-- 恆等式：Redis 餘量 + 未取消訂單的數量 = 活動總庫存
-- 不成立就代表有庫存洩漏
```

**恆等式**：

```
Redis 餘量 + Σ(PENDING_PAYMENT + PAID 訂單的 quantity) = total_stock
```

| 偏差方向 | 意義 |
|----------|------|
| 左邊 < 右邊 | **超賣**——扣減沒扣到，或訂單重複建立 |
| 左邊 > 右邊 | 少賣——退庫多退了，或訂單被關卻沒扣回 |

### 檢查 Outbox 是否堵塞

```sql
SELECT status, COUNT(*), MIN(created_at) AS oldest
FROM outbox_event GROUP BY status;
```

- `PENDING` 持續累積且 `oldest` 越來越舊 → 中繼器沒在跑（節點掛了？鎖沒釋放？）
- 出現 `DEAD` → 投遞重試耗盡，**這些事件的副作用永遠不會發生**，需人工重放

```sql
-- 人工重放：確認過原因後才執行
UPDATE outbox_event SET status = 'PENDING', retry_count = 0
WHERE status = 'DEAD' AND event_type = 'order.cancelled';
```

### 檢查 DLQ

```bash
docker exec -it flash-sale-kafka kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic seckill.order.create.DLT --from-beginning --max-messages 20
```

DLQ 裡的每一則訊息，都代表一筆**庫存已扣但訂單沒建**的洩漏。

---

## 根因定位

### 超賣的可能原因（依機率排序）

1. **有人繞過 Lua 直接操作 Redis。** 搜尋 `opsForValue().decrement`、`increment`——
   庫存的寫入路徑只該有 Lua 腳本與 `initialize`
2. **預熱用了 `force=true`。** 這會直接覆寫餘量，把已賣出的量抹掉。
   查日誌關鍵字「強制覆寫」，以及是否有人呼叫了 `POST /warm-up?force=true`
3. **Snowflake 節點編號重複。** 多副本用了相同的 `SNOWFLAKE_NODE_ID`
   會產生重複訂單號，`uk_order_no` 衝突會讓部分訂單建不出來（表現為少賣），
   但若唯一索引被移除過，就會變成超賣
4. **`request_id` 唯一索引不存在。** 確認 `SHOW INDEX FROM seckill_order`
5. **多個活動共用了同一個 `activityId`。** 鍵會互相覆寫

### 少賣（庫存被鎖住）的可能原因

1. **DLQ 有未處理訊息**——最常見。`SeckillCompensationConsumer` 沒在跑或一直失敗
2. **Outbox 有 `DEAD` 事件**——`order.cancelled` 事件沒投遞出去，退庫從未執行
3. **關單排程沒在跑**——查 `seckill:lock:expired-order` 鎖是否被某個已死的節點持有：
   ```bash
   docker exec -it flash-sale-redis redis-cli TTL "seckill:lock:expired-order"
   ```
   （Redisson 的鎖有看門狗續期，節點正常關閉會釋放；被 `kill -9` 則需等 lease 過期）
4. **`seckill:{a}:req` 的 TTL 早於庫存鍵**——冪等映射先過期，退庫時找不到痕跡而直接跳過

---

## 修復後的驗證

1. 重新核對恆等式，確認左右相等
2. 確認 `seckill_compensation_total{result="failure"}` 停止增長
3. 確認 `outbox_event` 沒有新的 `PENDING` 累積
4. **補一個回歸測試**——事故的價值在於它揭露了一個測試沒覆蓋到的路徑

---

## 事後必做

在 `RedisStockRepositoryTest` 或對應的測試補上這個案例。
**沒有補測試的修復不算完成**——同樣的問題會在下一次大促重演，
而那時當班的人可能已經不是你。

若事故揭露了某個架構假設不成立，新增一份 ADR 記錄前提的變化。
