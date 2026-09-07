# 高併發分散式秒殺系統

以 Java 21 + Spring Boot 3 實作的秒殺引擎，並在其上長成一個完整電商。
重點在**流量削峰**、**多級快取**、**併發控制**與**防超賣**。
架構是六角架構（Ports & Adapters）的模組化單體，分層依賴由 ArchUnit 在 CI 強制驗證。

> 每一個關鍵取捨都有對應的 [ADR](docs/adr/)。
> 程式碼說明「做了什麼」，ADR 說明「**為什麼不用另一種做法**」。

**怎麼讀這份文件**：想在十分鐘內理解設計，讀〈核心設計〉與〈技術難點〉就夠；
想改程式，再讀〈架構與流程〉與 [`CLAUDE.md`](CLAUDE.md) 的鐵則；
想知道「這樣做到底撐不撐得住」，看〈效能實測〉——每個數字都是本機量出來的，含踩到的坑。

---

## 全系統長什麼樣

```
                          瀏覽器 / 行動裝置
                                │
                    ┌───────────▼───────────┐
                    │  Nuxt 3（SSR + ISR）   │  漏斗第 0 層：靜態頁走 CDN，
                    │  BFF：refresh token   │  庫存數字另走輕量請求
                    │  收進 httpOnly cookie  │
                    └───────────┬───────────┘
                                │ /api/v1/**（JWT）
                    ┌───────────▼───────────┐
                    │   Spring Boot 單體      │  六角架構：api → infrastructure → application → domain
                    │   ┌─────────────────┐ │
                    │   │ 秒殺熱路徑       │ │  Caffeine 售罄標記 → 入場控制 → 多級快取
                    │   │ （零 DB）        │ │  → Redis Lua 扣減 → Kafka 投遞 → 202
                    │   └─────────────────┘ │
                    │   ┌─────────────────┐ │
                    │   │ 一般交易通道     │ │  MySQL 條件式 UPDATE + Outbox，同一交易，201
                    │   └─────────────────┘ │
                    │   9 個 Kafka 消費端    │  建單／補償／出貨／積分／通知／退款／索引／縮圖／銷量
                    │   12 個背景排程        │  Outbox 中繼、逾時關單、退款補送、對帳、預熱…
                    └──┬────┬────┬────┬────┘
                       │    │    │    │
             ┌─────────▼┐ ┌─▼──┐ ┌▼───┐ ┌▼──────────────┐
             │  Redis   │ │MySQL│ │Kafka│ │ Elasticsearch │   MinIO（商品圖）
             │ 秒殺庫存  │ │真實 │ │佇列 │ │ 搜尋讀模型     │
             │ 多級快取  │ │來源 │ │+DLT │ │               │
             │ 分散式鎖  │ └────┘ └────┘ └───────────────┘
             └──────────┘
                       ▲ 指標 / 追蹤 / 告警
             Prometheus + Grafana + Tempo（OTLP），整條建單鏈同一個 trace id
```

**兩條下單通道刻意不共用任何一段程式**：秒殺是「所有人搶同一件」，一般交易是
「數萬個 SKU 各自獨立」，流量特徵完全相反，用同一套機制必然有一邊做不好
（[ADR-0006](docs/adr/0006-dual-order-channels.md)）。

---

## 核心設計

### 削峰漏斗：四層過濾，一層比一層貴

秒殺的本質是「用極少的庫存承接極大的流量」。1000 件商品可能湧入百萬請求，
其中 99.9% 注定失敗。設計的重點不是讓成功的請求更快，
而是**讓注定失敗的請求以最低成本被擋下**。

```
                     100 萬 req/s
                          │
   ① 本機售罄標記         │   Caffeine，奈秒級，零網路
      擋下售罄後的洪峰     ▼
                     ~1 萬 req/s
                          │
   ② 多級快取讀活動       │   L1 Caffeine → L2 Redis → L3 MySQL
      業務規則校驗         ▼   （Decorator，應用層無感）
                     ~1 萬 req/s
                          │
   ③ Redis Lua 原子扣減   │   單次 RTT，全系統唯一強一致點
      防超賣的核心         ▼
                      1000 req（= 庫存量）
                          │
   ④ Kafka 投遞           │   單次 RTT，把建單移出請求鏈路
                          ▼
                     [ 非同步建單 ]  ← DB 壓力由消費並行度決定，與前端流量脫鉤
```

**熱路徑上沒有任何資料庫讀寫**——這是削峰能成立的根本原因。

### 防超賣：三層冪等

| 層級 | 機制 | 擋住什麼 |
|------|------|----------|
| Redis Lua | `requestId → orderNo` 映射 | 使用者連點、網路重送 |
| MQ 消費端 | `saveIfAbsent` 先查後寫 | Kafka at-least-once 的重複投遞 |
| 資料庫 | `request_id` 唯一索引 | 前兩層都失效時的最終防線 |

分散式系統中每一層都可能失效。最後一道防線必須是無條件成立的約束——
只有資料庫的唯一索引具備這個性質。

### 一致性：Outbox + Saga，不用 Seata

```
扣減庫存 ──▶ 投遞訊息 ──▶ 建立訂單 ──▶ 等待付款
    │            │             │            │
    │            │             │            └─逾時─▶ 關單 ─┐
    │            │             └─重試耗盡─▶ DLQ ──────────┤
    │            └─投遞失敗─▶ 立即退庫                     │
    └───────────────────── 退回庫存 ◀───────────────────────┘
```

訂單落庫與領域事件寫入 `outbox_event` **在同一個資料庫交易內**，天然原子，
不需要任何分散式交易協調者（[ADR-0004](docs/adr/0004-outbox-saga-over-seata.md)）。

圖上「投遞失敗 → 立即退庫」有一個容易做錯的細節：**等待逾時不是失敗**。
逾時的語意是「不知道送到沒」，生產者仍在自己的 `delivery.timeout.ms` 內重試；
據此退庫的話，庫存會被別人買走，而訂單稍後照樣建立——那是真實超賣。
只有「確定沒送出」才退，不確定時一律選少賣，讓對帳的孤兒偵測接手
（[ADR-0030](docs/adr/0030-publish-timeout-is-not-failure.md)）。

---

## 技術難點

這個系統真正難的地方不在任何一個元件，而在**分散式系統裡「失敗」有很多種長相**，
每一種都要有人接住，而且接的方式不能讓另一種失敗變得更糟。
下面每一條都是實作或壓測時真的踩到的，不是預想的。

| 難點 | 為什麼難 | 怎麼解 | 證據 |
|---|---|---|---|
| **超賣** | 「讀餘量 → 判斷 → 扣減」三步之間任何並行都會多賣 | 扣減與判斷寫進**同一支 Lua**，全系統只有這一個強一致點；三層冪等（Redis 映射／`saveIfAbsent`／唯一索引）擋重複 | 1000 執行緒搶 100 件，成功數剛好 100（`RedisStockRepositoryTest`） |
| **削峰** | 1000 件庫存湧入百萬請求，99.9% 注定失敗 | 四層漏斗由便宜到貴，**熱路徑零 DB**；建單推進 Kafka 讓 DB 壓力與前端流量脫鉤 | 受理 1,471/s vs 落庫 213/s，約 7:1；售罄後比有庫存時更快（1,460 vs 1,244 QPS） |
| **「不知道」不等於「失敗」** | Kafka 投遞逾時時生產者還在重試；當成失敗去退庫，訊息之後送達就超賣 | `TimeoutException` 與 `RetriableException` 回 `PENDING` 不退庫；只有序列化、訊息過大這類「連 append 都不會發生」的才退 | 把 `send-timeout` 調成 1ms 實機重現：舊碼退庫後訂單照建；新碼庫存 431540→431539、補償 0 次（[ADR-0030](docs/adr/0030-publish-timeout-is-not-failure.md)） |
| **退款的送達** | 佇列的重試預算只有幾秒，耗盡進死信，而退貨單早已寫成「已退款」——帳上退了、錢沒出去，且**查不出來** | 「已核可」與「已到帳」拆成兩個狀態（`REFUNDING → REFUNDED`），資料庫是工作項、佇列只是快車道；排程不受任何預算限制地推到成功 | 把一張單改回 `REFUNDING` 並把發起時間撥前一小時，下一輪排程即結算（[ADR-0031](docs/adr/0031-refund-settlement-is-a-durable-work-item.md)） |
| **兩套庫存不能各自認帳** | 同一個 SKU 秒殺與一般通道都在賣，兩個真實來源必然超賣 | 以「劃撥」隔開，先動 MySQL 再寫 Redis；預熱寫的是「總量 − 已售」而不是總量 | 容器重建後對帳報 `OVERSELL_RISK drift +4`，那 4 件正是重啟前賣掉的（[ADR-0008](docs/adr/0008-dual-inventory-model.md)） |
| **偏差不會自癒** | 最終一致沒有交易兜底，洩漏只會累積，而且沒有東西會告訴你 | 每 10 分鐘核對**三條**恆等式；只修能被證明安全的方向（孤兒扣減），`OVERSELL_RISK` 一律人工 | 第三條（劃撥支撐）是前兩條都帳平時仍漏掉的那種超賣，實作時真的踩到才補上 |
| **收款成功不可改寫** | 付款回調與逾時關單競態：錢收了但訂單已關 | 如實記 `SUCCEEDED` 再轉 `REFUND_PENDING`，兩個事件都發 | `payment_callback_total{result="refund-required"}` 恆為 0 才健康 |
| **一個熱門商品讓整條通道陪葬** | 等行鎖的請求握著連線，連線池被佔滿後不相干的請求也開不了交易 | 秒殺不共用同步通道；耗盡回 `503 retryable` 而非 `500` | 集中 vs 分散：29 vs 363 TPS，`Innodb_row_lock_waits` 1,444 vs 31 |
| **降級路徑把系統推進放大迴圈** | 熔斷打開後每個被擋請求印一份堆疊，日誌 I/O 讓呼叫更慢、熔斷更開 | 降級方法不印堆疊、必須 `public`（Resilience4j 反射呼叫）；ArchUnit 擋下 | 修正前 96 萬行日誌、51% 503；修正後 2,832 行、全部 409 |
| **分區鍵選錯，六個消費者只有一個在做事** | 秒殺依定義只有一場活動，用 `activityId` 分區等於單分區 | 分區鍵要選「同一個什麼必須有序」——是同一張訂單 | 78,037 則訊息全落 partition 6；改 `orderNo` 後 38 → 213 TPS（[ADR-0020](docs/adr/0020-order-create-partition-key.md)） |
| **一行 WARN 背後是 310 秒** | `@EntityGraph` 配上分頁時 Hibernate 把符合條件的**全部**載入再切，功能完全正常 | 兩段式：先用覆蓋索引取 ID，再依 ID join fetch；ArchUnit 擋下這個組合 | 逾時關單（庫存止血路徑）310 秒 → 3.5 秒，積壓清完 38 小時 → 4 小時 |
| **重試預算一體適用** | 消費端重試是阻塞式的，久留擋住同分區後面的人並撞上 `max.poll.interval.ms` | 預算依「失敗了要靠什麼救回來」分檔：有排程兜底的快檔、只能人工重建的慢檔 | 慢檔的錯誤處理器刻意不註冊成 Bean，否則 Boot 的 `getIfUnique()` 會讓預設容器安靜失去死信 |
| **撤銷令牌被自己的例外回滾** | 偵測到外洩 → 撤銷整條輪替鏈 → 拋例外拒絕，而例外讓外層交易連撤銷一起還原 | 撤銷走 `REQUIRES_NEW` 獨立交易 | mock 測試看到 `revokeFamily` 被呼叫就會過，**只有實機才抓得到** |
| **預設金鑰在正式環境靜靜跑著** | 三把 HMAC 金鑰的預設值在版控裡，拿到就能偽造身分、付款回調、搶購資格；警告在日誌洪流裡等於不存在 | `SecretGuard` 拒絕啟動，`dev` profile 才放行；方向刻意是「本機起不來很吵、正式漏掉很貴」 | 不帶 profile 啟動，在綁定連接埠之前中止並列出三把金鑰各自的後果 |
| **Outbox 切斷了追蹤** | 事件先落 DB、排程另外搬，observation 的自動傳播在那裡就斷了 | `outbox_event.trace_context` 存寫入時的 `traceparent`，中繼時還原 | 一張秒殺單在 Tempo 裡是 29 個 span，從 HTTP 入口一路接到事件消費（[ADR-0029](docs/adr/0029-distributed-tracing.md)） |

三個貫穿全部的原則：

1. **降級要看代價，不能一刀切。** 庫存服務故障 fail-closed（放行 = 無上限超賣），
   限流器故障 fail-open（後面還有庫存這道關），節點編號撞號拒絕啟動（誤判很吵但一改就好）。
2. **帳目要有一個格子承認「現實可能還沒跟上」。** `REFUND_PENDING`、`REFUNDING`、`PENDING`
   都是這種格子。先寫上樂觀的結果，之後就查不出哪些是假的。
3. **不確定時選少賣。** 少賣可以事後補救，超賣不行。

---

## 架構與流程

### 分層：依賴方向只能由外往內

**編譯期依賴**（`ArchitectureTest` 強制，違規在 CI 就擋下）：

```
api ──▶ infrastructure ──▶ application ──▶ domain
                                              零框架依賴
```

`application` 只宣告它需要什麼（Port 介面），實作由 `infrastructure` 提供。
所以**應用層拿不到 `RedisTemplate` 這個類別**——不是「不該用」，
而是模組依賴上就用不了。

**執行期的呼叫方向不一樣**，這是最容易看混的地方：

```
   HTTP
     │
     ▼
   api ─────▶ application（Use Case）
                     │
          ┌──────────┴───────────┐
          ▼                      ▼
       domain            infrastructure（Port 的實作）
    純運算，零 I/O                 │
                                  ▼
                    Redis / MySQL / Kafka / ES / S3
```

編譯期 `infrastructure` 在 `application` 外面，執行期卻是 `application`
呼叫它——**依賴反轉就是這個意思**。

一次搶購請求的實際軌跡：

```
SeckillController.seckill(@CurrentUser Long userId, SeckillRequest)   api
        │  身分取自 JWT 的 sub claim，不是請求體
        ▼
SeckillUseCase.attempt(SeckillCommand)                        application（介面）
        ▼
SeckillApplicationService.execute(...)                        application（實作）
        │  只認得 Port：ActivityRepository / StockRepository / MessagePublisher
        │
        ├──▶ SeckillActivity.ensurePurchasableAt(now)                   domain
        │       活動有沒有開賣、有沒有結束、是不是上架中——純運算
        │
        └──▶ RedisStockRepository.deduct(...)              infrastructure（配接器）
                Lua 腳本，唯一的強一致點
```

**業務規則在 domain，I/O 在 infrastructure，兩者都由 application 編排。**
「活動結束後不能下單」這條規則因此可以注入固定時鐘直接測，不必起 Redis。

### 模組結構

```
flash-sale-domain          純 Java，零框架依賴 ← ArchUnit 強制
  activity/ catalog/ order/ payment/ identity/ stock/
  promotion/ review/ membership/ aftersales/ shipping/ shared/

flash-sale-application     Use Case 編排 + Port 介面
  port/in/  入站埠      port/out/  出站埠      service/  實作

flash-sale-infrastructure  出站配接器
  adapter/out/redis/        Lua 扣減、Redisson 鎖、請求追蹤
  adapter/out/cache/        多級快取（Decorator）、售罄標記
  adapter/out/persistence/  JPA + Outbox
  adapter/out/search/       Elasticsearch 讀模型
  adapter/out/media/        S3 相容物件儲存
  adapter/{in,out}/mq/      Kafka 生產者、消費者、DLQ 補償
  scheduler/                Outbox 中繼、逾期關單、庫存預熱、對帳

flash-sale-api             HTTP 入站配接器 + 組裝根
```

### 秒殺下單：一次請求的完整路徑

`SeckillApplicationService.execute` 的六個步驟，**由便宜到昂貴**：

```
① rejectIfSoldOutLocally      Caffeine 本機標記        0 次網路
② rejectIfQueueOverloaded     入場控制                 0 次網路（排程每 5 秒取樣）
③ loadPurchasableActivity     L1 → L2 → L3            L1 命中則 0 次
④ orderNoGenerator.next()     Snowflake               0 次網路
⑤ deductStock                 Redis Lua               1 次 Redis  ← 全系統唯一強一致點
⑥ publishOrCompensate         markAccepted + Kafka    2 次 Redis + 1 次 Kafka
        │
        ▼
   202 Accepted { orderNo }
```

幾個關鍵：

- **① 與 ② 在任何遠端呼叫之前。** 秒殺 99.9% 的請求注定失敗，
  越早擋下越好——實測售罄路徑（1,460 QPS）比有庫存時（1,244）還快
- **③ 之後才發號。** 號碼發了卻沒扣到庫存只是浪費一個號，
  反過來（先扣後發）則會有扣減找不到對應訂單號的空窗
- **⑤ 回傳「重複」時直接回放既有訂單號**，不再往下走。
  同一個 `requestId` 重送永遠拿到同一張訂單，庫存只扣一次
- **⑥ 失敗要立刻退庫，但逾時不是失敗。** Kafka 確定投遞不出去代表訂單永遠不會被建立，
  此時不退庫，那份庫存就永久漏掉了；等待逾時則是「不知道送到沒」，退了會超賣
  （[ADR-0030](docs/adr/0030-publish-timeout-is-not-failure.md)）

> 熱路徑穩態下是 **3 次 Redis 往返 + 1 次 Kafka**。
> 其中 `markAccepted` 佔了 2 次（HSET 與 EXPIRE 分開送），
> 折進扣減的 Lua 或改用 pipeline 可以省掉一次——目前還沒做。

**沒有任何一步碰資料庫。** 這是削峰能成立的根本原因，
也是 CLAUDE.md 把「熱路徑禁止 DB 讀寫」列為鐵則的理由。

### 一般下單：同一個問題的相反答案

```
POST /api/v1/orders  ──▶  OrderPlacementService.place   @Transactional
                            │
                            ├ ① requestId 已有訂單？→ 直接回傳（冪等第一層）
                            ├ ② 解析收貨地址 → 快照進訂單
                            ├ ③ 解析訂單行 → 商品名與單價快照
                            ├ ④ 扣庫存（MySQL 條件式 UPDATE）
                            ├ ⑤ 定價：促銷 → 券 → 運費
                            ├ ⑥ 核銷券
                            ├ ⑦ saveIfAbsent（冪等第三層：唯一索引）
                            └ ⑧ 事件寫進 outbox_event ← 同一個交易
                            │
                            ▼
                     201 Created  完整訂單
```

**④ 排在 ⑤ 之前是刻意的**：庫存不足是最常見的失敗，先擋掉就不必為註定失敗的請求算優惠；
而且券的核銷排在最後，扣庫存失敗時交易一起回滾，券自然不會被消耗掉。

**這條通道完全不需要補償**。任一步失敗，資料庫回滾就是補償——
連同前幾行已扣的庫存一起還原。秒殺那條需要的 Outbox 補償、DLQ、對帳兜底，
在這裡一個都用不上（[ADR-0006](docs/adr/0006-dual-order-channels.md)）。

### 事件流：一個 Outbox，九個消費端

系統有**兩條進 Kafka 的路，而它們不能互換**：

```
  秒殺熱路徑                              有資料庫交易的地方
  （沒有 DB 交易）                         （建單、付款、出貨、退貨…）
       │                                         │
       │ 直接 publish                             │ 與資料寫入同一個交易
       ▼                                         ▼
  seckill.order.create                     ┌──────────────┐
  （12 分區，鍵=orderNo）                    │ outbox_event │
       │                                   └──────┬───────┘
       │                                          │ 每 1 秒，跨節點互斥
       ▼                                          ▼
  SeckillOrderConsumer ×6                  OutboxRelayScheduler
       │                                          │
       │ 建單（落庫）                               ▼
       └───────────────────────────────▶  seckill.order.event
                                            （6 分區）
                                                  │
                        ┌─────────────────────────┼─────────────────┐
                        ▼                         ▼                 ▼
                  補償 / 出貨 / 銷量         積分 / 通知 / 退款    索引 / 縮圖
```

**熱路徑用不了 Outbox**，因為 Outbox 的全部意義是「與資料庫寫入同一個交易」，
而熱路徑上一次 DB 都不能碰。所以它改用「投遞失敗就當場退庫」——
用一個補償動作換掉一次資料庫往返。

反過來，**只要有交易可用的地方就一律走 Outbox**：訂單存在與下游收到通知
是同一件事，不需要分散式交易協調者（[ADR-0004](docs/adr/0004-outbox-saga-over-seata.md)）。

| 消費端 | group | 觸發事件 | 併行 | 做什麼 |
|---|---|---|---|---|
| `SeckillOrderConsumer` | `seckill-order-creator` | `order.create` 主題（非 Outbox） | 6 | 非同步建單 |
| `SeckillCompensationConsumer` | `seckill-stock-compensator` | `order.cancelled` | 3 | 退回 Redis 庫存 |
| `FulfillmentConsumer` | `fulfillment-shipment-creator` | `order.paid` | 2 | 建立出貨單 |
| `ProductSalesConsumer` | `product-sales` | `order.paid` | 2 | 累計銷量 |
| `MembershipConsumer` | `membership-points` | `order.completed` | 2 | 發積分、更新等級 |
| `NotificationConsumer` | `notification-dispatcher` | paid / shipped / completed / cancelled / refund | 2 | 寫入待發通知 |
| `RefundConsumer` | `aftersales-refund-executor` | `refund.requested` | 1 | 執行退款 |
| `ProductIndexConsumer` | `catalog-search-indexer` | `product.index-changed` | 1 | 同步 Elasticsearch |
| `ImageVariantConsumer` | `catalog-image-variants` | `product.image-attached` | 1 | 產生縮圖 |

**每個 group 各自消費整個主題**，彼此不影響——積分掛掉不會拖到出貨。

重試預算分兩檔，依「失敗了要靠什麼救回來」而不是依重要性：快檔（約 7.5 秒）給另有救援管道的
消費端——建單的積壓本身是服務水準、退款有補送排程；慢檔（約 90 秒）給失敗後只能人工重建的
搜尋索引與縮圖。兩檔都壓在 `max.poll.interval.ms` 之下，否則消費端會被踢出群組。

新增消費端時要回答兩個問題（CLAUDE.md 鐵則 4）：「重複投遞會怎樣」與
「**把歷史全部重跑一次會怎樣**」。第二題是因為 `auto-offset-reset: earliest`，
新的 group 第一次上線會重放整個主題——會員積分那個消費端上線時就這樣
追溯處理了所有歷史訂單。答案不是「沒事」的動作（寄信、扣款、呼叫外部 API）
就不該放在消費端裡。

### 訂單生命週期

四個狀態機各自獨立，以事件相連——**訂單只記里程碑，細節在各自的聚合裡**：

```
訂單  PENDING_PAYMENT ──▶ PAID ──▶ SHIPPED ──▶ COMPLETED
            │                │        │           │
            │                └────────┴───────────┴──▶ REFUNDED（終態）
            ├──逾時／取消──▶ CANCELLED（終態）
            └──────────────▶ FAILED（終態）

付款  PENDING ──▶ SUCCEEDED ──▶ REFUND_PENDING ──▶ PARTIALLY_REFUNDED ──▶ REFUNDED
          └──▶ FAILED

出貨  READY ──▶ IN_TRANSIT ──▶ DELIVERED
                   ▲ │
                   └─┴──▶ FAILED（可重送，不是終態）

退貨  REQUESTED ──▶ APPROVED ──▶ RECEIVED ──▶ REFUNDING ──▶ REFUNDED
          └──▶ REJECTED    └──▶ CANCELLED       （錢還沒出去）
```

退貨的 `REFUNDING` 與付款的 `REFUND_PENDING` 是同一個形狀：**當現實與帳目可能不同步時，
帳目要有一個格子承認這件事**。少了它，閘道故障時帳上說退了、錢沒出去，
而 `status = 'REFUNDED'` 裡成功與失敗的紀錄長得一模一樣
（[ADR-0031](docs/adr/0031-refund-settlement-is-a-durable-work-item.md)）。

**`PAID → CANCELLED` 是被禁止的**，而這條線很容易畫錯：取消會發出
`order.cancelled` 讓補償服務退庫，但錢已經收了——那會製造出
「庫存退了、錢沒退」的路徑，而逾時關單排程隨時可能踩到它。
已付款要退錢一律走退貨，那有自己的狀態機。

判準是：**訂單狀態只收錄「會改變買家能做什麼」的轉折**。
`PAID → SHIPPED` 要收錄（出貨前可自由取消，出貨後必須走退貨）；
「運送中 → 派送中」不收錄（買家能做的事沒變）。
這條線一鬆掉，訂單狀態機會長成物流狀態的副本，而副本永遠慢一步。

**配送失敗不是終態**，與訂單刻意鎖死終態是不同的取捨：訂單終態牽涉金流與庫存，
回頭一次就可能多退一次錢；配送失敗只是「東西還在路上」，重試沒有不可逆的副作用。

### 補償：四道防線，一道比一道慢

秒殺是最終一致的，所以每一種失敗都要有人接住：

| 失敗 | 誰接住 | 多久 |
|---|---|---|
| Kafka **確定**投遞失敗 | `publishOrCompensate` 當場退庫 | 毫秒 |
| Kafka 投遞**逾時** | 不退庫；訊息最終遺失時由對帳的孤兒偵測撈出 | 寬限期後 |
| 消費端重試耗盡 | DLQ + `DomainEventDeadLetterConsumer` | 秒 |
| 退款進了死信 | `RefundSettlementScheduler` 依 `REFUNDING` 狀態補送 | 60 秒一輪 |
| 使用者沒付款 | `ExpiredOrderScheduler` 關單 → `order.cancelled` → 退庫 | 30 秒一輪 |
| 以上全部失效 | `StockReconciliationService` 對帳 | 10 分鐘一輪 |

**最後一道只在能被證明安全時才自動修**（孤兒扣減，且已過寬限期）。
`OVERSELL_RISK` 方向一律人工——下修餘量會讓進行中的合法請求無故失敗。

### 背景排程

全部採跨節點互斥（`tryExecuteWithLock`），例外在表格裡註明：

| 排程 | 週期 | 做什麼 |
|---|---|---|
| `OutboxRelayScheduler` | 1 秒 | 把 PENDING 事件投進 Kafka |
| `ExpiredOrderScheduler` | 30 秒 | 逾時關單並退庫 |
| `NotificationDeliveryScheduler` | 30 秒 | 寄出待發通知 |
| `PaymentRefundScheduler` | 60 秒 | 掃描待退款的付款單（閘道呼叫在交易之外，一筆一次交易） |
| `RefundSettlementScheduler` | 60 秒 | 補送已核可但錢還沒出去的退款（[ADR-0031](docs/adr/0031-refund-settlement-is-a-durable-work-item.md)） |
| `StockWarmupRunner` | 60 秒 | 補上缺失的 Redis 庫存鍵 |
| `QueueDepthScheduler` | 5 秒 | 取樣建單佇列深度（**不互斥**：每個節點各自要有樣本） |
| `SnowflakeNodeIdGuard.renew` | 10 秒 | 續自己的節點編號租約（**不互斥**：加鎖反而會讓租約過期） |
| `StockReconciliationScheduler` | 10 分鐘 | 三條庫存恆等式對帳 |
| `SearchIndexReconciliationScheduler` | 15 分鐘 | 比對 ES 與資料庫 |
| `StockReleaseScheduler` | 30 分鐘 | 活動結束後把未售量還回可售池 |
| `RefreshTokenCleanupScheduler` | 每日 04:15 | 清掉過期的 refresh token |

新增排程時**跨節點互斥是必答題**——問法與消費端冪等完全相同：
「同時被跑兩次會怎樣？」`NotificationDeliveryScheduler` 就漏過一次，
兩個節點會讓使用者收到兩封一樣的信，而紀錄上只有一筆。

---

## 效能實測

以下數字全部量自 **2026-09-06 的本機實測**，種入 50,004 商品 / 100,007 SKU / 225 類目。
壓測工具（autocannon）跑在**本機原生**而非容器裡——容器多一跳網路，
而這裡要量的正是延遲本身，多出來的零點幾毫秒會直接混進 p99。

> **MySQL、Redis、Kafka、Elasticsearch、JVM 與壓測工具全部擠在同一台機器上**，
> 絕對數值必然被壓低。有意義的是**比例與曲線形狀**，不是絕對的 QPS。
> 重跑方式見 [`benchmark/`](benchmark/)。

### 秒殺熱路徑

50 萬庫存、60 個帳號、單使用者限流已解除（不解除的話量到的是限流器而不是系統）。
**回應全部是 `202`，沒有任何 4xx／5xx。**

| 併發 | TPS | p50 | p90 | p99 | p99.9 | max |
|---|---|---|---|---|---|---|
| 50 | 1,114 | 77ms | 143ms | 194ms | 230ms | 262ms |
| 200 | 1,244 | 164ms | 206ms | 254ms | 489ms | 665ms |
| 500 | 1,471 | 334ms | 397ms | 640ms | 845ms | 902ms |

吞吐量在 1.1k–1.5k 之間就打平了，延遲隨併發線性上升——這是**已經飽和**的形狀，
而不是「還能再撐」。單機同時跑五個中介軟體，這個上限來自 CPU 而不是設計。

**`202` 是「受理」不是「落庫」，而這個差距正是削峰的全部意義：**

| | 速率 | 決定於 |
|---|---|---|
| 前端受理 | **1,471 /s** | 一次 Redis + 一次 Kafka，與資料庫無關 |
| 消費端落庫 | **213 /s** | 消費並行度（預設 6）與資料庫寫入能力 |

**約 7:1。** 前端可以承接七倍於資料庫寫入能力的流量，多出來的排在 Kafka 裡。
這個比例就是漏斗第 ④ 層買到的東西——不加大資料庫，只是不讓它決定使用者等多久。
（佇列深度本身是有服務等級的，見 [ADR-0023](docs/adr/0023-queue-depth-as-service-level.md)。）

### 售罄之後——漏斗第 ① 層

秒殺 99.9% 的請求注定失敗，所以**「賣完之後有多快」比「賣的時候有多快」更重要**。

| 情境 | 併發 | QPS | p50 | p90 | p99 | 狀態碼 |
|---|---|---|---|---|---|---|
| 有庫存（實際扣減） | 200 | 1,244 | 164ms | 206ms | 254ms | 全部 202 |
| 已售罄（本機標記短路） | 200 | 1,460 | 141ms | 175ms | 215ms | 全部 409 |

售罄後**比有庫存時更快**，因為它連 Redis 都不用碰——Caffeine 的本機標記直接回絕。

### 讀路徑（50 併發 × 12 秒）

| 情境 | QPS | p50 | p90 | p99 | p99.9 | 非 2xx |
|---|---|---|---|---|---|---|
| 商品列表（第一頁） | 1,108 | 85ms | 128ms | 157ms | 175ms | 0 |
| 商品列表（隨機翻頁） | 1,161 | 80ms | 130ms | 158ms | 174ms | 0 |
| 商品列表（依類目，含子樹） | 730 | 77ms | 144ms | 165ms | 191ms | 0 |
| **商品列表（深分頁 offset 4 萬）** | **1,153** | **79ms** | **133ms** | **156ms** | **193ms** | 0 |
| 商品詳情（隨機 5 萬筆） | 1,295 | 75ms | 122ms | 162ms | 181ms | 0 |
| 類目樹（225 節點） | 887 | 95ms | 132ms | 179ms | 216ms | 0 |
| 搜尋（Elasticsearch） | 1,422 | 62ms | 97ms | 112ms | 468ms | 0 |
| 進行中的活動 | 3,032 | 66ms | 114ms | 146ms | 164ms | 0 |

**深分頁那一列是重點**：翻到第 2000 頁與翻到第一頁**幾乎一樣快**。
`OFFSET 40000` 要資料庫掃過並丟棄四萬列，而 keyset 用複合游標直接定位
（[ADR-0021](docs/adr/0021-keyset-pagination.md)）。

「依類目」較慢是因為它做的是**含子樹**的篩選——點「3C 產品」要看得到底下所有孫類目的商品，
點了卻只有直屬商品是使用者不會回報但會直接離開的那種壞掉
（[ADR-0022](docs/adr/0022-category-subtree-filter.md)）。

### 前端 SSR（node-server，50 併發）

| 頁面 | QPS | p50 | p90 | p99 |
|---|---|---|---|---|
| `/products/25000`（詳情，可快取） | 1,354 | 35ms | 43ms | 55ms |
| `/products`（列表，可快取） | 900 | 52ms | 66ms | 85ms |
| `/`（首頁，可快取） | 853 | 55ms | 69ms | 86ms |
| `/cart`（**刻意不可快取**） | 212 | 227ms | 267ms | 428ms |

差距 4–6 倍，而這正是 `routeRules` 買到的東西。`/cart` 慢是**對的**：
購物車是每個人不同的資料，快取它等於把某個人的購物車發給下一個訪客。

### 一般下單（同步交易通道）

秒殺那條路徑刻意繞開資料庫，而這條刻意用它。所以它的天花板就是資料庫的天花板——
問題只在「什麼時候撞到」。ADR-0008 的前提是
「數萬個 SKU 各自獨立、衝突率極低，DB 完全夠用」，這裡就是去驗證那句話。

| 情境 | 併發 | TPS | p50 | p90 | p99 | 狀態碼 |
|---|---|---|---|---|---|---|
| 分散（10 萬 SKU 隨機） | 20 | 242 | 81ms | 150ms | 295ms | 全部 201 |
| 分散（10 萬 SKU 隨機） | 50 | 346 | 131ms | 195ms | 324ms | 全部 201 |
| 分散（10 萬 SKU 隨機） | 100 | **363** | 245ms | 373ms | 691ms | 全部 201 |
| 分散（10 萬 SKU 隨機） | 200 | 321 | 588ms | 837ms | 1,578ms | 全部 201 |
| 集中（全部搶同一個 SKU） | 20 | **29** | 664ms | 713ms | 1,075ms | 全部 201 |
| 集中（全部搶同一個 SKU） | 50 | 30 | 1,611ms | 1,699ms | 2,117ms | 全部 201 |
| 集中（全部搶同一個 SKU） | 100 | 27 | 3,309ms | 3,804ms | 6,556ms | 5 筆 503 |
| 集中（全部搶同一個 SKU） | 200 | 45 | 3,332ms | 6,973ms | 8,616ms | 277 筆 503 |

**同樣的程式、同樣的機器，只差在買不買同一件商品：363 → 29 TPS，掉了 12 倍。**

`Innodb_row_lock_waits` 講的是同一件事：分散情境四輪共 **31 次**，
集中情境四輪 **1,444 次**，累計鎖等待時間 32 分鐘。

**而崩潰的方式比數字更值得看。** 完整機制是：

```
所有請求搶同一個 inventory 列
        │
        ▼  條件式 UPDATE 取得排他行鎖
等在行鎖上的請求「一直握著自己的資料庫連線」
        │
        ▼
Hikari 連線池（50）被排隊的人佔滿
        │
        ▼
後面的請求連交易都開不起來 ── 包含要買別的商品、
                              根本不碰這一列的請求
```

**一個熱門商品能讓整條同步下單通道陪葬**，不只是那個商品變慢。
這正是為什麼秒殺不能共用這條路：秒殺的定義就是「所有人搶同一件」。

> **順手修掉一個回錯狀態碼的問題。** 連線池耗盡原本落到兜底處理回 `500`，
> 而客戶端看到「系統異常」不會重試——但這恰恰是重試就會好的那種錯誤。
> 改成 `503` 並標記 `retryable`（與樂觀鎖衝突回 409 同一個判準：
> 預期中的結果不該長得像系統故障）。修正前 330 筆 500，修正後同樣的負載全是 503。

**這條通道還沒有入場控制。** 秒殺有佇列深度做入場管制，
一般通道只能靠連線池自然排隊——而排隊的代價是連線被佔住。
正確的解法是營運層面的：**單一 SKU 的併發到某個量級就該改走秒殺通道**，
那本來就是它存在的理由。

### 壓測抓到的問題

這些都在低流量下完全正常，只有壓上去才現形。

**① 降級路徑比正常路徑還貴**（本次修正）

熔斷器打開時，降級方法對**每一個**被擋下的請求印一份完整堆疊。
12 秒的壓測產出 96 萬行日誌、1 萬份講同一件事的堆疊。
而日誌 I/O 讓呼叫變慢 → 觸發「慢呼叫」門檻 → 熔斷器更開 → 更多請求走進降級路徑。
**系統自己把自己推進了一個放大迴圈。**

同一段程式還有第二個問題：降級方法宣告成 `private`，
而 Resilience4j 是從它自己的套件反射呼叫的——`IllegalAccessException`
被包成 `UndeclaredThrowableException` 往外丟，於是**該回 503 的請求變成 500**。
低流量下永遠看不到：熔斷器不開，降級方法就一次也不會被呼叫。

| 售罄路徑（200 併發） | 修正前 | 修正後 |
|---|---|---|
| QPS | 882 | **1,460** |
| p50 | 253ms | **141ms** |
| p90 | 4,054ms | **175ms** |
| 狀態碼 | 51% 503 + 少量 500 | **全部 409** |
| 日誌行數 | 962,178 | **2,832** |

熔斷器修正後**完全不再打開**——那 51% 的 503 從頭到尾都是自己造成的。
`ArchitectureTest.fallbackMethodsMustBePublic` 現在會擋下 `private` 那一半。

**② Kafka 分區鍵選了 `activityId`**（[ADR-0020](docs/adr/0020-order-create-partition-key.md)）

秒殺活動**依定義就只有一個活動**，於是全部訊息落在同一個分區，
六個消費者只有一個在做事。當時實測 78,037 則訊息**全部落在 partition 6**，
其餘 11 個分區是空的：建單 38 TPS，而入口每秒接得下 1,448 筆。

改用 `orderNo` 之後，同一組配置量到 **213 筆/秒**（本次壓測數字，見上表）。
分區鍵要選的是「**同一個什麼必須有序**」——是同一張訂單，不是同一場活動。
訂單號高基數、均勻散佈，而且同一張訂單的重投仍然落在同一分區。

**③ 商品列表的 N+1**

`toDomain` 碰到了延遲載入的 `getSkus()`，而 `asSummary()` 隨即把它們丟掉——
量到 `size=100` 時打出 102 次 SELECT。改用投影後固定為常數次查詢。
**寫的時候完全看不出來**，因為那一行長得像單純的型別轉換。

**④ 連線池耗盡回 500 而不是 503**（本次修正，見上一節）

熱門商品把連線池佔滿之後，後續請求連交易都開不起來，
落到兜底處理變成「系統異常」。那會讓客戶端不去重試一個重試就會好的錯誤，
同時把真正的程式錯誤淹沒在尖峰噪音裡。

**⑤ 一行 WARN 背後是 310 秒**

`@EntityGraph` 配上 `Limit` 時 Hibernate 只印一行 `HHH90003004`，然後把符合條件的
資料**全部**載入再於記憶體裡切出 limit 筆。資料庫裡有 89,100 筆逾期未付款訂單，
關單排程每輪只要 200 筆，卻要把全部載進 persistence context——而且整段跑在
`@Transactional` 裡，commit 時還要對那 89,100 個實體做 dirty check。

| | 修正前 | 修正後 |
|---|---|---|
| 每輪 200 筆耗時 | **310 秒** | **約 3.5 秒** |
| 清完 89,100 筆積壓 | 約 38 小時 | 約 4 小時（大半是排程的 30 秒固定延遲） |

這條路徑是庫存的止血動作：訂單沒關，那份秒殺庫存就一直被佔著賣不出去。
改成兩段式（先用覆蓋索引取 ID，再依 ID join fetch），並加一條 ArchUnit 規則擋住這個組合——
它是個安靜的陷阱，功能完全正常，只在資料量長大後表現成「排程好像越跑越慢」。

---

## 快速開始

```bash
docker compose up -d                      # MySQL / Redis / Kafka / ES / MinIO / Prometheus / Grafana / Tempo
mvn spring-boot:run -pl flash-sale-api -Dspring-boot.run.profiles=dev
cd web && npm install && npm run dev
```

> **`dev` profile 不是可選的。** 三把簽章金鑰（JWT、付款回調、搶購資格）的預設值就在版控裡，
> `SecretGuard` 在沒有覆寫時**拒絕啟動**，只有 `dev` profile 才放行。
> 方向刻意是這一邊：忘了加 profile 只是本機起不來，一改就好；
> 反過來讓正式環境靜靜用著預設金鑰，代價是整套認證與風控形同不存在。

| 服務 | 位址 |
|------|------|
| 前端 | http://localhost:5173 |
| API 文件 | http://localhost:8080/swagger-ui.html |
| Grafana | http://localhost:3000 （匿名可看） |
| Prometheus | http://localhost:9090 |
| MinIO 主控台 | http://localhost:9001 |

啟動時自動跑 Flyway migration、植入示範活動（`1001`–`1003`）並把庫存預熱到 Redis。
clone 下來即可直接搶購。

> 前端埠是 5173 而非 Nuxt 預設的 3000——後者被 `docker-compose.yml` 的 Grafana 佔用。

### 試一次搶購

```bash
curl -X POST localhost:8080/api/v1/auth/register -H "Content-Type: application/json" -d '{"email":"a@b.com","password":"password123","displayName":"A"}'
```

```bash
curl -X POST localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d '{"email":"a@b.com","password":"password123"}'
```

```bash
curl -X POST localhost:8080/api/v1/seckill/orders -H "Content-Type: application/json" -H "Authorization: Bearer <accessToken>" -d '{"activityId":1001,"quantity":1,"requestId":"demo-001"}'
```

回 `202 Accepted` 與訂單號，再以訂單號輪詢結果（尚未落庫時回 `PROCESSING`，而非 404）。
**重送相同的 `requestId` 會拿到同一張訂單**，庫存只扣一次。

### 進後台

註冊出來的帳號一律是 `CUSTOMER`，而系統裡**沒有任何端點能提升角色**——
提權端點自己就需要 admin 權限，那是先有雞還是先有蛋。
第一個管理員因此由設定注入，啟動時建立：

```bash
BOOTSTRAP_ADMIN_EMAIL=ops@example.com BOOTSTRAP_ADMIN_PASSWORD=change-me-please \
  mvn spring-boot:run -pl flash-sale-api -Dspring-boot.run.profiles=dev
```

以這組帳密登入後，導覽列會出現「後台」，即 `/admin`。

三件事值得知道：

- **只在系統中還沒有任何管理員時生效一次。** 已經有管理員就完全不動作——
  否則這份設定會變成一條永久有效的提權後門：拿到環境變數的人隨時能把
  任意帳號變成管理員，而且看起來完全像正常啟動
- **信箱已註冊過的話是「提升」而非「建立」，且不覆寫密碼。**
  順手重設密碼會讓這份設定多一個能力：覆寫任意既有帳號的密碼
- **沒有預設值。** 不設就不啟用；只設信箱沒設密碼會**當場讓啟動失敗**，
  而不是安靜略過——那會讓人以為建好了，直到打不開後台才發現

正式環境用完應該把設定移掉，之後的管理員由已有的管理員在後台指派。

> 前端的 `/admin` 路由守衛**不是安全邊界**，它只決定看不看得到後台的殼。
> 改 JS 就能讓入口出現，但那沒有意義——`/api/v1/admin/**` 仍然要
> `seckill:admin` scope（[ADR-0015](docs/adr/0015-operations-console.md)）。

---

## API

完整清單見 Swagger UI。以下是各領域的入口與存取層級：

| 領域 | 路徑 | 存取 |
|------|------|------|
| 秒殺 | `/api/v1/seckill/orders` | Bearer；`202` + 訂單號，需輪詢 |
| 一般下單 | `/api/v1/orders`、`/orders/checkout` | Bearer；`201` + 完整訂單（同步） |
| 商品目錄 | `/api/v1/catalog/**` | 匿名 |
| 搜尋 | `/api/v1/search/{products,suggestions}` | 匿名（Elasticsearch 讀模型） |
| 活動 | `/api/v1/activities` | 匿名；餘量取自 Redis 即時值 |
| 購物車 / 地址 | `/api/v1/cart/**`、`/api/v1/addresses/**` | Bearer |
| 付款 | `/api/v1/orders/{no}/payments` | Bearer；回調由簽章驗證 |
| 促銷 / 優惠券 | `/api/v1/coupons/**` | Bearer |
| 評價 / 評分 | `/api/v1/catalog/products/{id}/reviews`、`/api/v1/reviews/**` | 讀匿名、寫 Bearer |
| 會員 / 積分 | `/api/v1/membership/**` | Bearer |
| 退貨退款 | `/api/v1/orders/{no}/returns`、`/api/v1/returns/**` | Bearer |
| 履約出貨 | `/api/v1/orders/{no}/shipment` | Bearer |
| 通知 | `/api/v1/notifications/**` | Bearer |
| 管理端 | `/api/v1/admin/**` | `seckill:admin` scope |

`SecurityConfig` 以 `anyRequest().authenticated()` 收尾，放行清單**逐一列出路徑**——
用 `/**` 一次放行會連還沒寫的端點也一起開放。

### 認證

**OAuth2 Resource Server + JWT**，身分取自標準的 `sub` claim
（[ADR-0005](docs/adr/0005-jwt-resource-server-over-custom-filter.md)）。
令牌雙軌，因為無狀態與可撤銷是互斥的：

| | Access token | Refresh token |
|---|---|---|
| 形式 | JWT（自包含） | 不透明隨機字串 |
| 有效期 | 15 分鐘 | 7 天 |
| 驗證 | 純 CPU，零遠端呼叫 | 查資料庫 |
| 可撤銷 | ❌ | ✅ |

每次續期都**輪替**，舊的立即失效。已輪替過的 token 再度出現代表憑證外洩，
系統撤銷**整條輪替鏈**。撤銷走 `REQUIRES_NEW` 獨立交易——
否則拒絕請求的例外會把撤銷一起回滾掉，變成偵測到外洩卻什麼都沒撤銷。
**那個 bug 是實機驗證才發現的**，mock 測試看到 `revokeFamily` 被呼叫就會判定通過。

選 JWT 而非 Session 只有一個理由：**驗證是純 CPU 運算，不需要遠端呼叫**。
Session 每個請求都要讀一次 Redis，等於在熱路徑上憑空增加一次往返。
由此推論出一條鐵則：**絕不可為了取得使用者資料而在認證環節查資料庫**。

前端的 `/admin` 路由守衛**不是安全邊界**，它只決定看不看得到後台的殼；
繞過之後打到的每一支 API 仍會被 `hasAuthority(SCOPE_ADMIN)` 擋下
（[ADR-0015](docs/adr/0015-operations-console.md)）。

---

## 幾個做過的取捨

### 雙下單通道：202 與 201 的差別不是風格問題

兩條路徑的走法見上面的〈架構與流程〉，這裡只講為什麼不統一。

`202` 的意思是「收到了，還沒做」——秒殺回它是誠實的，訂單真的還沒建立。
一般下單回 `201` 也是誠實的，交易已提交、訂單確實存在。
**統一成同一個狀態碼就是對其中一邊說謊。**

一般通道刻意做成同步：它沒有削峰需求，推進 MQ 換來的是「為什麼買一本書也要輪詢」，
而且會失去交易帶來的免費正確性（[ADR-0006](docs/adr/0006-dual-order-channels.md)）。

### 庫存雙模型：兩套機制，以「劃撥」隔開

秒殺與一般商品的流量特徵完全相反，用同一套機制必然有一邊做不好。
但**同一個 SKU 兩邊都在賣、而兩個真實來源必然超賣**，因此用劃撥隔開：

```
劃撥 N 件   available -= N,  allocated += N        總量不變
秒殺進行中  只動 Redis                              MySQL 不參與
一般銷售    只動 available                          Redis 不參與
活動結束    allocated -= N,  available += 未售量     總量減少 = 實際銷量
```

順序上**一律先動 MySQL 再寫 Redis**——反過來的失敗模式是「Redis 有貨、MySQL 沒扣」，
那是超賣；正著來最壞是少賣，而少賣可以事後補救（[ADR-0008](docs/adr/0008-dual-inventory-model.md)）。

預熱寫的是**總庫存 − 已售出**，不是總庫存。直接寫總量的話 Redis 一重啟就把已賣出的量抹掉，
再賣一次同一批貨——實測踩過，容器重建後對帳報出 `OVERSELL_RISK drift +4`，
那 4 件正是重啟前賣掉的。

### 快照或引用：同一個問題，三個地方三種答案

| | 存什麼 | 問的問題 |
|---|---|---|
| 訂單金額、品名 | **快照** | 「當初成交是多少錢」 |
| 收貨地址 | **快照**（`ShippingInfo`，非 `addressId`） | 「這張訂單當初要寄到哪裡」 |
| 購物車 | **引用**（只存 SKU 與數量） | 「現在買要多少錢」 |

購物車存價格快照，商家調價後使用者會看到舊價格卻被收新價格。
訂單用引用，歷史訂單會在調價當下集體變動。地址存 ID，使用者搬家後
三個月前已送達的訂單會顯示成寄到新家——那是出貨紀錄被竄改。
**把同一套規則套到三邊，一定有兩邊是錯的。**

`Address`（Identity）與 `ShippingInfo`（Ordering）**刻意互不認得**，轉換在應用層。
ArchUnit 只管分層，抓不到這種脈絡耦合，只能靠 review。

### 讀寫並發：條件式 UPDATE 與唯一索引，不是分散式鎖

系統裡有六處需要「同一份資料被多人同時改」，全部走同一個手法——
**把判斷條件寫進 UPDATE 的 WHERE，或交給唯一索引**，沒有一處用分散式鎖：

庫存扣減、券核銷、領券、評分聚合、積分兌換、銷量計入。

鎖的問題不是慢，是**把並行度壓成 1**，而這幾條路徑正是流量最集中的地方
（[ADR-0003](docs/adr/0003-lua-atomicity-over-distributed-lock.md)、
[ADR-0013](docs/adr/0013-promotion-pricing-engine.md) 決策 6）。

聚合計數一律用 `SET x = x + ?` 的增量 UPDATE。寫成「SELECT 出來、在 Java 裡加、UPDATE 回去」
是 read-modify-write：兩個人同時評價，兩邊都讀到 count=10、各自寫回 11，
於是有一則評價從聚合上消失——而評價表裡還在。

`product_rating` 存的是**總和與筆數，不是平均值**：存平均就重建不出原始事實
（[ADR-0014](docs/adr/0014-review-and-rating-aggregate.md) 決策 2）。

### 金額：退款按行退，運費不進 totalAmount

`Order.totalAmount` 是**商品**折後應付，而 `totalAmount == Σ allocatedAmount`
這條恆等式是退款按行退的基礎。運費不分攤到行（三件一起寄、退掉一件，
沒有「三分之一趟」），因此它是獨立欄位，付款一律用 `order.payableAmount()`
（[ADR-0019](docs/adr/0019-shipping-fee-model.md)）。

有折扣的訂單，退款必須按**分攤後的實付**退。整單折扣折在訂單上、退貨卻是退一行，
用 `unitPrice × quantity` 退的是使用者沒付過的錢。`Payment` 的退款上限攔不住這件事——
全額退貨會超過上限被擋下，**部分退貨不會**（[ADR-0013](docs/adr/0013-promotion-pricing-engine.md)）。

### 會員等級用累計實付，不是積分餘額

用餘額算會讓使用者一花積分就降級，而花積分正是我們希望他做的事——
那會把整個機制的激勵方向反過來。

退貨必須扣回積分與累計消費，否則「買了再退」就能免費升級，
而等級決定回饋倍率，那是可以無限重複的套利。扣回以**流水裡那一筆原始入帳**為基準，
不可用當下的等級重算（[ADR-0016](docs/adr/0016-membership-points-and-tiers.md)）。

`member_account.point_balance` **刻意允許為負**：退款絕不能因為「積分不夠扣」而失敗——
那會變成「錢退不了」。負餘額是真實的債務，兌換的守衛條件會擋住他再換。

### 收款成功絕不可被改寫成失敗

付款完成時訂單已被逾時關單搶先關閉，是真實會發生的競態。此時錢**確實收了**：

| 處理方式 | 為什麼不行 |
|---|---|
| 標記為失敗 | 帳上寫「沒收到」而現實是收到的，對帳永遠對不平 |
| 強制把訂單改回 PAID | 庫存已退回並可能被別人買走，那是超賣 |
| **如實記錄成功，再轉 `REFUND_PENDING`** | ✅ 本方案 |

**兩個事件都要發**（`payment.succeeded` + `payment.refund-required`），
只發後者會讓下游看到一筆沒有對應收入的支出。
`payment_callback_total{result="refund-required"}` 應恆為 0。

> 目前是模擬金流（`SimulatedPaymentGateway`），但**模擬的是流程而非走捷徑**：
> 非同步回調、簽章驗證、回調重送冪等三者都是真的。走同步捷徑的話，
> 這些情境只會在接上真實金流後才第一次出現，而那是最糟的發現時機。

### 其他已經決定過的事

| 想做的改動 | 先讀 |
|------------|------|
| 「庫存應該放資料庫才對」 | [ADR-0002](docs/adr/0002-stock-in-redis-not-database.md) |
| 「應該拆成微服務」 | [ADR-0001](docs/adr/0001-modular-monolith-hexagonal.md) |
| 「訂單存一個總額就好」 | [ADR-0007](docs/adr/0007-multi-line-order-aggregate.md) |
| 「搜尋直接查 MySQL」 | [ADR-0012](docs/adr/0012-search-read-model.md) |
| 「折扣存一個總額就好」 | [ADR-0013](docs/adr/0013-promotion-pricing-engine.md) |
| 「退款直接改原訂單金額」 | [ADR-0011](docs/adr/0011-refund-saga.md) |
| 「深分頁用 offset 就好」 | [ADR-0021](docs/adr/0021-keyset-pagination.md) |
| 「類目篩選只看直屬」 | [ADR-0022](docs/adr/0022-category-subtree-filter.md) |
| 「圖片存資料庫 / 即時縮放」 | [ADR-0027](docs/adr/0027-product-media-storage.md) |
| 「佇列積壓只是效能問題」 | [ADR-0023](docs/adr/0023-queue-depth-as-service-level.md) |
| 「風控放在熱路徑上做」 | [ADR-0028](docs/adr/0028-seckill-qualification-and-risk-control.md) |
| 「投遞逾時就退庫比較安全」 | [ADR-0030](docs/adr/0030-publish-timeout-is-not-failure.md) |
| 「退款重試次數調大就好」 | [ADR-0031](docs/adr/0031-refund-settlement-is-a-durable-work-item.md) |

---

## 庫存對帳

最終一致的系統沒有資料庫交易兜底，**偏差不會自癒，只會累積**，
而且沒有任何東西會主動告訴你。每 10 分鐘核對三條恆等式：

```
① 秒殺   Redis 餘量 + Σ(PENDING_PAYMENT + PAID 訂單數量) = 活動總庫存
② 一般   available = Σ 流水的 availableDelta   （allocated 同理）
③ 劃撥   Redis 有庫存的活動，MySQL 必須有對應的劃撥額度撐著
```

**三條缺一不可**，因為它們問的是不同的問題：① 問「賣掉的有沒有被記錄」，
② 問「MySQL 這邊的帳對不對」，③ 問「這批貨到底是不是我們的」。
前兩條可以同時完全帳平而第三條不成立——那代表 Redis 握著一批從沒為它付過帳的貨，
秒殺賣一次、一般通道再賣一次。**這條是實作時真的踩到才補上的。**

| 偏差方向 | 判定 | 後果 | 處置 |
|---|---|---|---|
| 實際 < 應有 | `STOCK_LEAKED` | 少賣，庫存被鎖住 | 可自動修復（僅孤兒扣減） |
| 實際 > 應有 | `OVERSELL_RISK` | **超賣，不可逆** | 一律人工介入 |
| 庫存無劃撥支撐 | `OVERSELL_RISK` | **超賣，不可逆** | 一律人工介入 |

**孤兒扣減**（庫存扣了、訂單卻不存在）是最危險的一種洩漏，只有主動掃描才找得到。
判定需同時滿足：查無此訂單號，**且**訂單號的產生時間已超過寬限期。
第二個條件不可省略——剛產生幾秒的訂單很可能只是還在 MQ 佇列裡排隊，
此時退庫，等訊息真的被消費時就從少賣變成了超賣。
寬限期能成立靠的是 Snowflake 訂單號**自帶產生時間**（選它而非 UUID 的附帶好處）。

**自動修復預設關閉**，因為「自動修復」與「自動破壞」之間只隔著一個 bug。
只有能被證明安全的偏差才納入：孤兒扣減有明確判定依據，且修復方向只會「歸還」庫存。
反方向一律不自動處理——下修餘量會讓進行中的合法請求無故失敗。
一般庫存對帳（`InventoryReconciliationService`）**完全不做自動修復**：
那裡的偏差本身就代表有東西繞過了正規路徑，「自動修正」等於用一個猜測覆蓋另一個猜測。

---

## 測試

```bash
mvn test                                             # 全部
mvn test -pl flash-sale-domain,flash-sale-application   # 快速回饋（無需 Docker）
mvn test -pl flash-sale-infrastructure               # Redis / ES 整合測試（需 Docker）
mvn test -pl flash-sale-api -Dtest=ArchitectureTest  # 架構約束
cd web && npm test                                   # 前端（Vitest，約 2 秒）
```

| 測試 | 驗證什麼 |
|------|----------|
| `RedisStockRepositoryTest` | **1000 執行緒搶 100 件庫存，成功數必須剛好 100** |
| `RedisStockRepositoryTest$Compensation` | 退庫冪等——重複退只生效一次 |
| `SeckillApplicationServiceTest` | 確定投遞失敗必須退庫；**逾時絕不可退庫**；補償失敗不可掩蓋原始錯誤 |
| `KafkaSeckillMessagePublisherTest` | 哪一種例外算「確定沒送出」——判錯就是超賣，應用層的 mock 測不到它 |
| `RefundExecutionServiceTest` | 閘道成功才結算；失敗留在 `REFUNDING`；重放整個 topic 不會重複退錢 |
| `StockReconciliationServiceTest` | 偏差方向判定、孤兒寬限期、「什麼情況絕不自動修」 |
| `PaymentRefunderTest` | 閘道回失敗不落庫、例外不逸出——一筆退不掉不該讓其他人的錢也卡著 |
| `SecretGuardTest` | 預設金鑰拒絕啟動、三把都要列出、`dev` profile 放行 |
| `ArchitectureTest` | 10 條分層、依賴與框架約定規則（含「join fetch 不可與分頁併用」），違規在 CI 就被擋下 |
| `SeckillControllerSecurityTest` | 沒帶令牌必須被擋；身分取自令牌而非請求內容 |
| `ErrorCodeTest` | 錯誤碼不可重複——前端靠它決定要不要重試 |

併發測試對著**真實的 Redis**（Testcontainers）執行。
這一段用 mock 等於 mock 掉唯一要驗證的東西——測試會全綠，超賣照樣發生。

---

## 可觀測性

| 指標 | 用途 |
|------|------|
| `seckill_attempt_duration_seconds` | 端到端延遲，含 P50/P95/P99 |
| `seckill_rejection_total{code}` | 拒絕原因分佈，區分「賣太好」與「壞掉了」 |
| `seckill_compensation_total{result}` | **`result="failure"` 必須恆為 0**，非零代表庫存被永久鎖住 |
| `seckill_stock_drift{activity}` | **對帳偏差，恆為 0 才健康**；> 0 代表超賣風險 |
| `seckill_orphan_binding_total{action}` | 孤兒扣減的偵測與修復結果 |
| `seckill_queue_depth` | 建單佇列深度，入場控制的依據（[ADR-0023](docs/adr/0023-queue-depth-as-service-level.md)） |
| `seckill_qualification_total{result}` | 資格預檢結果；被拒比例過高是誤殺、過低是沒擋到（[ADR-0028](docs/adr/0028-seckill-qualification-and-risk-control.md)） |
| `seckill_publish_total{outcome}` | 投遞結果；`pending` 不是錯誤，但持續偏高代表 `send-timeout` 太緊或 broker 變慢 |
| `refund_awaiting_settlement_total` | 已核可但錢還沒出去的退款；短暫非 0 正常，**持續非 0 代表閘道卡住** |
| `outbox_dead_total` | 投遞已放棄的 Outbox 事件；**恆為 0 才健康**，非 0 代表有下游動作永遠不會發生 |

標籤只用 `activityId` 與錯誤碼，**絕不放 `userId`**——那會讓時間序列數量爆炸。

告警規則見 [`deploy/prometheus/alert-rules.yml`](deploy/prometheus/alert-rules.yml)。
每一條都對應一個「有人要在半夜起床處理」的狀況；
會響但沒人需要行動的告警，只會訓練團隊忽略所有告警。

### 分散式追蹤

指標回答「整體有多慢」，追蹤回答「**這一筆**為什麼慢」。
Micrometer Tracing + OpenTelemetry bridge，OTLP 送到 Tempo，在 Grafana 的 Explore 查
（[ADR-0029](docs/adr/0029-distributed-tracing.md)）。

一筆秒殺的 trace 長這樣：`POST /seckill/orders` → `redis`（Lua 扣減）→ `seckill.order.create send`
→ `seckill.order.create receive`（消費端）→ `jdbc`（建單）→ `outbox.relay` → 通知／積分／出貨的消費端。

Outbox 是刻意切斷的（事件先落 DB、排程另外搬），observation 的自動傳播在那裡會斷。
`outbox_event.trace_context` 存下寫入時的 `traceparent`，中繼時還原——整條鏈仍是同一個 trace id。

每一行 log 都帶 `traceId`，從一行 ERROR 直接貼進 Grafana 就是整條鏈。
訂單也記下建單當下的 trace id：後台訂單展開就有「在 Tempo 查看整條建單鏈」的連結，
客服查「這張單為什麼卡住」從翻日誌變成點一下。

### 後台即時監控

`/admin/activities/{id}` 每兩秒取一次快照：Redis 餘量與售罄標記直讀（不經快取，
監控頁看到的必須是真的）、建單佇列積壓與預估等待、成功／拒絕／錯誤、拒絕碼分布、
投遞 acked/pending/failed、補償與落庫結果、p95/p99。計數是**本節點**的指標暫存值，
每秒速率由前端從累計值差分算出；跨節點的總和請看 Grafana。

---

## 前端

Nuxt 3 + Vue 3 + TypeScript，詳見 [`web/`](web/)。

| 區塊 | 路徑 |
|------|------|
| 首頁入口 | `/`（輪播、限時搶購、本週熱銷 TOP 5、分類、版位由後台設定） |
| 排行榜 | `/rankings`（本週／本月，只算已付款的單） |
| 秒殺 | `/seckill/[id]`（倒數、庫存輪詢、開賣抖動、領資格） |
| 商品 | `/products`、`/products/[id]`、`/search`（價格區間／星等／有貨／排序，全部住在網址裡） |
| 交易 | `/cart`、`/checkout`、`/orders`、`/orders/[orderNo]`（再買一次） |
| 帳戶 | `/account`（總覽：訂單狀態格、券、收藏、通知、最近看過）、`/member`（積分）、`/coupons`、`/reviews`、`/addresses`、`/notifications`、`/history` |
| 售後 | `/returns`、`/returns/[returnNo]` |
| 後台 | `/admin/{orders,members,risk,shipments,returns,questions,products,activities,activities/[id],promotions,home,reports,ops}` |

這一頁本身就是**削峰漏斗的第 0 層**：靜態部分走 ISR + Nitro 快取，
庫存數字走獨立的輕量請求，開賣瞬間加隨機抖動把請求打散。

令牌採 BFF 設計——`server/api/auth/*` 把 refresh token 攔進 httpOnly cookie，
瀏覽器只拿得到 access token 且只存在記憶體中。

### 幾個視覺決定

- **中文刻意不下載 webfont。** 一套 CJK 字檔動輒 1–2 MB，而這個站的賣點就是首屏速度。
  中文交給系統字體，只把拉丁字母與數字（Archivo / IBM Plex Mono）從 CDN 取。
- **數字一律等寬**（`.figure`）。價格、庫存、倒數、單號是這個介面的主要內容，
  非等寬會讓倒數每秒把版面推來推去。
- **卡片用 1px 邊框而非陰影。** 這是要讓人看清楚數字的介面，密集列表上的陰影只會浮躁。
- **手機版有底部固定操作列。** 主要動作永遠在拇指構得到的地方，
  而不是跟著內容捲走——那是手機轉換率最常見的漏水點。
- **顏色全部走 CSS 變數**，元件裡沒有任何 `dark:` 前綴——漏掉一個就會出現
  「深色背景配深色文字」這種只在其中一個主題下才看得到的 bug。

型別目前仍是手寫的（`app/types/api.ts`）。實測已證明這會漂移——
後端把 `ActivityView.productId` 改成 `skuId` 時，前端型別安靜地留在舊欄位上。
下一步應改由 OpenAPI 產生，讓契約變動在**編譯期**就失敗。

### `isr` 單獨給是不夠的

`routeRules` 的 `isr` 是**平台層的指示**——Vercel／Netlify 會在建置產物裡讀它，
而自架的 node-server preset 既不建立快取、也不送出任何 `cache-control`。
實測只寫 `isr: 300` 時，`/products` 每次請求都仍然打後端，50 併發下只有 76 QPS。

因此公開頁面**兩個都給**：`isr` 對應部署到平台的情況，`cache`（Nitro 自己的機制）
對應自架，同時把 `cache-control` 送出去給前面的 CDN。修正後同一條路徑跳到 832 QPS。

`'/orders/**': { isr: false }` 則不是效能取捨而是**安全邊界**：
訂單是每個使用者專屬的資料，被 CDN 快取等於把某個人的訂單發給下一個訪客。
它要在部署到 CDN 之前就存在，不是等出事之後才補。

---

## 演進規劃

路線圖與架構主張見 [`docs/roadmap/`](docs/roadmap/)。核心主張是
**秒殺是特例通道，不是骨幹**——現有設計都建立在「流量極大、庫存極小、
99.9% 請求注定失敗」這個前提上，而一般電商的流量特徵完全相反。

尚未實作：真實金流串接、電子發票、超商取貨、帳號匿名化、管理員操作稽核日誌、評價審核、
後台儀表板（待辦事項與系統健康——那些指標都在 Prometheus 裡，後台卻看不到）。

---

## 給 AI 協作者的自動化配置

`.claude/` 目錄把架構約束與操作流程沉澱成可執行的資產：

| 類型 | 用途 | 位置 |
|------|------|------|
| **Instructions** | 每次對話都載入的鐵則（分層、熱路徑禁忌、冪等要求） | [`CLAUDE.md`](CLAUDE.md) |
| **Skills** | 特定任務的標準流程（改 Lua、加 Use Case、壓測、事故排查） | [`.claude/skills/`](.claude/skills/) |
| **Hooks** | 檔案存檔當下的即時守門，比等 CI 快 | [`.claude/hooks/`](.claude/hooks/) |
| **Agents** | 專職審查者（併發正確性、熱路徑效能） | [`.claude/agents/`](.claude/agents/) |

設計原則見 [`.claude/README.md`](.claude/README.md)。

---

## 技術棧

Java 21 · Spring Boot 3.3 · Redis 7（Lua）· Redisson · Kafka 3.7 ·
MySQL 8 · Flyway · Elasticsearch 8 · MinIO（S3）· Resilience4j ·
Micrometer + Prometheus + Grafana · Testcontainers · ArchUnit ·
Nuxt 3 · Vue 3 · TypeScript · Tailwind
