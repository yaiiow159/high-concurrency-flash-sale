---
name: add-domain-event
description: 新增領域事件與 MQ 消費端的完整流程。當要新增 DomainEvent、新增 Kafka topic 或消費者、修改 Outbox 相關程式碼、或處理 DLQ 與消費端冪等時使用。涵蓋事件契約設計、Outbox 原子寫入、消費端冪等實作與死信處理。
---

# 新增領域事件

這個系統以 Outbox 模式取代分散式交易。理解一件事就能理解整條鏈路：
**Outbox 保證事件不遺失，代價是「至少一次」——因此消費端冪等不是可選項。**

---

## 完整鏈路

```
聚合根狀態變更
    │  order.cancel(...) → registerEvent(OrderCancelledEvent)
    ▼
應用層在交易內取出並寫入 Outbox
    │  eventOutbox.append(order.pullDomainEvents())
    │  ← 與訂單 UPDATE 在同一個 commit，天然原子
    ▼
OutboxRelayer 非同步搬到 Kafka
    │  ← 至少一次語意從這裡開始
    ▼
消費端處理（必須冪等）
    │  重試耗盡 → DLQ → 補償
    ▼
副作用完成
```

---

## 步驟

### 1. 定義事件

`flash-sale-domain/.../order/event/XxxEvent.java`

```java
public record XxxEvent(
        String eventId,      // UUID，同時是消費端的冪等鍵
        String orderNo,
        // ... 業務欄位
        Instant occurredAt
) implements DomainEvent {

    public static final String TYPE = "order.xxx";

    @Override public String eventType() { return TYPE; }
    @Override public String aggregateId() { return orderNo; }  // MQ 分區鍵
}
```

**事件必須攜帶消費端需要的所有資料，不要讓消費端反查資料庫。**
消費時的資料庫狀態可能已經又變了，反查等於處理一份與事件不一致的快照。

`OrderCancelledEvent` 帶著 `requestId` 就是這個道理——
退庫需要它做冪等判斷，若消費端才去查訂單表，訂單可能已被清理。

**欄位只增不改。** 事件是跨行程契約，佇列中可能還躺著舊版本的訊息。
`ObjectMapper` 已設定 `FAIL_ON_UNKNOWN_PROPERTIES=false`，讓新增欄位不會打爆舊消費端。

### 2. 在聚合根登記事件

```java
public void doSomething(Instant now) {
    transitionTo(OrderStatus.XXX);      // 先驗證狀態轉移合法
    registerEvent(XxxEvent.of(this, now));
}
```

**只在 `create()` 與行為方法中登記，`restore()` 絕不可登記事件**——
否則 Repository 每次載入既有訂單都會噴出一堆假事件。

### 3. 應用層在交易內寫入 Outbox

```java
@Transactional
public void someUseCase(...) {
    order.doSomething(clock.instant());
    orderRepository.update(order);
    eventOutbox.append(order.pullDomainEvents());   // 同一個交易
}
```

`JpaEventOutbox` 標了 `Propagation.MANDATORY`——**沒有交易就直接拋錯**。
這是刻意的：若寫成 `REQUIRED`，某天有人在交易外呼叫它，
事件會脫離業務資料獨立 commit，Outbox 的原子性保證會**靜默瓦解**。

`pullDomainEvents()` 取出後會清空，確保同一事件不會被寫入兩次。

### 4. 消費端

`infrastructure/adapter/in/mq/XxxConsumer.java`

```java
@KafkaListener(topics = KafkaTopics.ORDER_EVENT, groupId = "...", concurrency = "...")
public void onEvent(@Payload String payload,
                    @Header(name = KafkaTopics.HEADER_EVENT_TYPE, required = false) String eventType) {
    if (!XxxEvent.TYPE.equals(eventType)) {
        return;   // 同一 topic 承載多種事件，非本組關心的直接 ack
    }
    useCase.handle(objectMapper.readValue(payload, XxxEvent.class));
}
```

**消費端要極薄**：反序列化、路由、委派。業務邏輯全在 Use Case，
這樣測試業務邏輯完全不需要 Kafka。

**不要寫 try-catch 吞例外。** 錯誤處理由 `DefaultErrorHandler` 統一負責
（指數退避重試 → DLQ）。自己吞掉例外等於讓失敗訊息被靜默 ack，
副作用永遠不會發生，而且沒有任何痕跡。

### 5. 冪等（必答題）

至少一次語意下，重複投遞是**常態不是異常**。三種可用策略：

| 策略 | 適用 | 範例 |
|------|------|------|
| 唯一索引 | 有落庫動作 | `saveIfAbsent` + `request_id` 唯一鍵 |
| 狀態機守衛 | 狀態轉移 | 已是終態就拋 `ILLEGAL_ORDER_STATE_TRANSITION` |
| 外部標記 | 無落庫的副作用 | Lua 的 `requestId → orderNo` 映射 |

**天然冪等的操作不需要額外機制**（例如「設為已取消」重複執行結果相同），
但要確認它真的天然冪等——「庫存 +1」就不是。

### 6. 新增 topic 時

在 `KafkaTopics` 加常數，並在 `KafkaConsumerConfig` 加 `NewTopic` bean。

**分區數決定消費端的最大並行度**，且**只能增不能減**。
建議選能被多個數字整除的值（如 12），讓消費者副本數在擴縮容時都能均勻分配。

DLQ 一律以 `.DLT` 為後綴（`DeadLetterPublishingRecoverer` 的預設慣例），
分區固定為 0——DLQ 的量極小，維持與原 topic 相同的分區數只會產生大量空分區。

### 7. 死信處理

**進了 DLQ 的訊息必須有人處理。** 只寫進去不消費，等於把問題藏起來。

參考 `SeckillCompensationConsumer.onOrderCreateDeadLetter`：
訂單根本沒建成，資料庫裡沒有任何紀錄會提醒你「這裡有庫存被鎖住」，
只能依訊息本身攜帶的 `requestId` 退庫。**這是最容易被遺漏、後果卻最嚴重的路徑。**

---

## 檢查清單

- [ ] 事件攜帶消費端所需的全部資料，不需反查資料庫
- [ ] 事件欄位只增不改（跨版本相容）
- [ ] `restore()` 沒有登記事件
- [ ] `eventOutbox.append()` 在交易內呼叫
- [ ] 消費端冪等策略已明確選定並實作
- [ ] 消費端沒有 try-catch 吞例外
- [ ] 新 topic 已加入 `KafkaTopics` 與 `NewTopic` bean
- [ ] DLQ 有對應的消費端，且會實際處理而非只記錄
- [ ] 事件為 record 且可被 `ObjectMapper` 正確序列化（含 `Instant`）

---

## 常見錯誤

**在交易外呼叫 `append()`。** 會被 `MANDATORY` 擋下並拋錯——這是好事，
代表保護機制生效了。修法是把呼叫端納入交易，而不是把 `MANDATORY` 改成 `REQUIRED`。

**在同一個交易裡退 Redis 庫存。** Redis 不會跟著回滾。交易失敗時庫存已經退了，
結果是憑空多出庫存——**這是超賣，比少賣更嚴重**。
正確做法是先 commit 事件，再由消費端執行退庫。

**忘記事件是至少一次。** 「這個事件應該只會來一次吧」——不會的。
broker 重平衡、消費者重啟、位移提交失敗，任何一個都會導致重複投遞。

**在事件裡塞聚合根。** 序列化聚合根會把私有欄位與不變條件一起帶出去，
之後任何重構都會打爛佇列中的舊訊息。事件必須是扁平、穩定的資料契約。
