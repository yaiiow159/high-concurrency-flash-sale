# ADR-0030：投遞逾時不是投遞失敗

- 狀態：已接受
- 日期：2026-09-07
- 相關：[ADR-0002](0002-stock-in-redis-not-database.md)（庫存放 Redis）、
  [ADR-0004](0004-outbox-saga-over-seata.md)（Outbox + Saga）、
  [ADR-0020](0020-order-create-partition-key.md)（分區鍵）

---

## 背景

秒殺熱路徑的最後一步是把建單訊息投遞到 Kafka，投遞失敗就立刻退回剛扣掉的庫存——
不退的話那份庫存永遠不會變成訂單，也永遠不會回到可售池，是永久少賣。這個補償是對的。

問題在於「投遞失敗」是怎麼判斷的：

```java
kafkaTemplate.send(...).get(sendTimeout, MILLISECONDS);   // 500ms
...
} catch (ExecutionException | TimeoutException e) {
    throw new BusinessException(MESSAGE_PUBLISH_FAILED, ...);   // 兩者同一個結論
}
```

`ExecutionException` 是「**確定沒送出**」。
`TimeoutException` 是「**我不想再等了**」——完全不同的一件事。

而生產者的設定是 `acks=all`、`enable.idempotence=true`、`linger.ms=5`（當時還有 `retries: 3`，見決策 3）。
我們在 500ms 放棄等待之後，**生產者還會繼續重試**，直到它自己的 `delivery.timeout.ms`。

### 這條路徑會怎麼超賣

```
1. Lua 扣減：庫存 -1，寫下憑證 requestId → orderNo|userId|quantity
2. send().get(500ms) → TimeoutException
3. 補償退庫：HDEL 憑證、INCRBY 庫存 +1        ← 這份庫存現在別人買得到
4. 生產者重試成功，訊息落地
5. 消費端 createFrom：查活動、建訂單、saveIfAbsent 成功
                      ↑ 完全不回頭檢查憑證還在不在
6. 結果：庫存已退回（可能已被買走）＋ 訂單成立
```

這正是 `StockReconciliationService` 會報出的 `OVERSELL_RISK`，
而[鐵則 8](../../CLAUDE.md) 明訂那個方向一律不自動處理——也就是說，
**這個 bug 造成的偏差，安全網不會替我們修**。

順帶一提，使用者收到的訊息是「訂單受理失敗，庫存已退回」，
而他其實會在訂單列表裡看到一張訂單。訊息本身也是假的。

---

## 決策

### 1. 只有「確定沒送出」才退庫

出站埠的契約寫死這件事：

> 實作只在「確定沒有送出」時拋例外。等待逾時不算失敗——
> 生產者仍在自己的 delivery.timeout 內重試，此時回傳 `PENDING`。

- `ExecutionException` → 拋例外 → 退庫（行為不變，它本來就是對的）
- `TimeoutException` / `InterruptedException` → 回 `PENDING` → **不退庫**，照常回 202

`send-timeout` 的角色因此變成純粹的**延遲預算**（我們願意讓熱路徑等多久），
不再兼任**正確性判斷**（訊息到底送出去了沒）。這兩件事本來就不該由同一個數字決定。

### 2. 不知道的時候，選擇少賣而不是超賣

`PENDING` 之後有兩種結局：

| 結局 | 後果 |
|---|---|
| 訊息其實送達了 | 訂單正常建立，使用者輪詢到結果——**完全正確** |
| 訊息最終真的沒送達 | 憑證留著、訂單不存在 → 對帳的孤兒偵測撈出來 |

第二種情況下那份庫存會被鎖住，直到孤兒寬限期（30 分鐘）過後被偵測、
由人決定要不要放（`auto-repair-orphans` 預設關閉）。那是少賣。

**少賣可以事後補救，超賣不行。** 這與鐵則 8 對 `OVERSELL_RISK` 的立場一致：
不確定的時候，寧可讓庫存卡住等人來看，也不要放出一份可能已經賣掉的量。

### 3. 「確定沒送出」不能靠 `ExecutionException` 這個型別判斷

第 1 點的正確性壓在一句話上：`ExecutionException` = 確定沒送出。
**那句話不是 Kafka 給的保證。** `NotEnoughReplicasAfterAppendException` 的字面意思就是
「已經 append 到 leader 但副本數不足」——ISR 恢復後那筆紀錄會變成可見，
而它會以 `ExecutionException` 的形式回到我們手上。

先前設定裡有 `retries: 3`，讓這種可重試的錯誤在約 300ms 就耗盡重試、
在我們的 500ms 之前變成終局失敗，於是走上退庫——**這條路徑會讓超賣以另一個形式回來**。

兩層修正：

1. **拿掉 `retries`**。冪等生產者的預設是 `Integer.MAX_VALUE`，
   讓 `delivery.timeout.ms` 單獨決定何時放棄（這也是 Kafka 官方對 `retries` 的建議）。
2. **分類 cause 而不是分類型別**：`RetriableException` 的子類一律當 PENDING，
   只有序列化、訊息過大、主題不存在、無權限這種「連 append 都不會發生」的才算失敗。
   這一層讓正確性不再依賴生產者的參數搭配。

### 4. 時間預算的相依關係要寫下來——而且要誠實

```
stock.key-ttl-buffer (2h)
  >  orphan-grace-period (30m)
  >  max( 消費端積壓, delivery.timeout.ms (2m), payment-window (15m) )
  >  send-timeout (500ms)
```

`delivery.timeout.ms` 先前沒有明訂，靠 Kafka 的預設值。現在明訂在設定裡——
一個沒有寫下來的下界遲早會被人調破：把寬限期調到 1 分鐘看起來很合理，
但那會把**還在重試路上**的請求誤判成孤兒而退庫，於是我們又回到了超賣。

**這條不等式目前並不成立，要誠實記下來。** 孤兒判準用的是訂單號的 Snowflake 時戳，
也就是**扣減的時刻**，不是訊息被消費的時刻。而 [ADR-0023](0023-queue-depth-as-service-level.md)
實測過建單積壓 46 分鐘——那 46 分鐘裡，每一筆訊息還好端端躺在 Kafka 裡的請求，
在第 30 分鐘都會被判成孤兒。

在 `auto-repair-orphans=false`（目前的預設）下這只是告警噪音。
但**開啟自動修復之前必須先滿足兩個前提**，否則它會退掉訊息還在路上的扣減：

- `ADMISSION_CONTROL_ENABLED=true`，且 `admission.max-wait-seconds` 遠小於寬限期
  （入場控制存在的意義就是把積壓壓在一個上界內）
- 或者把 `orphan-grace-period` 拉到大於實測的最壞積壓

第二條同樣沒寫下來的界是 `stock.key-ttl-buffer > orphan-grace-period`：
憑證的 TTL 對齊庫存鍵，而對帳只掃 `now - keyTtlBuffer` 內結束的活動。
buffer 一旦被調到小於寬限期，孤兒偵測會**安靜地完全失效**——
憑證與活動都已離開視野，而沒有任何指標會變。

### 5. 這件事要看得見

新增 `seckill_publish_total{outcome}`（acked / pending / failed）。

`pending` 不是錯誤，但它的比例是一個訊號：持續偏高代表 `send-timeout` 訂得太緊，
或 broker 正在變慢。先前這個狀態根本不存在——它被算成 failed 然後被退庫了。

---

## 被否決的方案

**消費端建單前回頭確認憑證還在。** 方向是對的（憑證才是「這筆扣減仍然有效」的權威紀錄），
但要正確就必須是原子的「檢查並認領」，否則只是把競態窗口變窄：
消費端檢查到憑證存在 → 補償刪掉憑證並退庫 → 消費端建單，同樣超賣。

做成原子認領（再一支 Lua）之後又長出新問題：認領完但資料庫交易回滾怎麼辦？
憑證被認領了、訂單不存在，而補償會拒絕釋放已認領的憑證——變成另一種洩漏。
要補上「DLQ 路徑可以強制釋放」的例外，整個協定就從兩個狀態變成四個。

**用更長的 send-timeout。** 這只是把窗口變窄，沒有消除它；
而且它直接加在熱路徑的延遲上，代價由 100% 的請求承擔，為的是一個罕見情況。

**逾時後掛 callback，等生產者最終結果再決定退不退。** 語意上最精確，也想過。
但 callback 跑在生產者的 IO 執行緒上，在那裡打 Redis 會阻塞所有其他訊息的投遞；
要正確就得再跳一次執行緒池，而那個池的飽和又是一個新的失效模式。
用一個已經存在、已經被測試過的安全網（孤兒偵測），比新增一條非同步路徑便宜得多。

---

## 後果

- 熱路徑的行為不變：仍然最多等 500ms，仍然回 202
- 使用者在 `PENDING` 時看到的是「處理中」而不是假的「已退回庫存」——前端本來就會輪詢
- 訊息真的遺失時，那份庫存會卡住到孤兒偵測報出來為止。
  想讓它自動放，要開 `auto-repair-orphans`（預設關閉是刻意的，見鐵則 8）
- `orphan-grace-period` 從此有一個明確的下界，改它之前要先看 ADR
- 重複請求（同一個 `requestId` 重送）現在會**再投遞一次**同樣的訊息。
  不重投的話會有一個卡死：首次請求走了 PENDING 而訊息最終遺失時，憑證會留著，
  於是重送永遠命中冪等分支、永遠不會有訊息被投出去。
  這條路徑**絕不補償**——重複代表憑證還在，而首次請求可能已經建好單了
