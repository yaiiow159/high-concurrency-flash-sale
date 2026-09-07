# 高併發分散式秒殺系統

以 Java 21 + Spring Boot 3 實作的秒殺引擎，並在其上長成一個完整電商。
重點在**流量削峰**、**併發控制**與**防超賣**，架構是六角架構的模組化單體，分層依賴由 ArchUnit 強制驗證。

> 每一個關鍵取捨都有對應的 [ADR](docs/adr/)。程式碼說明「做了什麼」，ADR 說明「**為什麼不用另一種做法**」。
> 效能數字與踩到的坑在 [`docs/performance.md`](docs/performance.md)，改程式前的鐵則在 [`CLAUDE.md`](CLAUDE.md)。

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

---

## 架構設計

### 一句話

秒殺的本質是「用極少的庫存承接極大的流量」——1000 件商品湧入百萬請求，99.9% 注定失敗。
設計重點不是讓成功的請求更快，而是**讓注定失敗的請求以最低成本被擋下**。

### 削峰漏斗：四層過濾，一層比一層貴

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
實測受理 1,471/s、落庫 213/s，約 7:1：前端承接七倍於資料庫寫入能力的流量，多出來的排在 Kafka 裡。

### 兩條下單通道，刻意不共用任何一段程式

| | 秒殺 | 一般交易 |
|---|---|---|
| 流量特徵 | 所有人搶同一件 | 數萬個 SKU 各自獨立 |
| 庫存 | Redis Lua | MySQL 條件式 UPDATE |
| 建單 | 非同步（Kafka） | 同步（同一交易） |
| 回應 | `202` + 訂單號，需輪詢 | `201` + 完整訂單 |
| 失敗處理 | 補償、DLQ、對帳 | 資料庫回滾就是補償 |

`202` 是「收到了，還沒做」，`201` 是「交易已提交」——**統一成同一個狀態碼就是對其中一邊說謊**。
一般通道推進 MQ 換來的是「為什麼買一本書也要輪詢」，還失去交易帶來的免費正確性
（[ADR-0006](docs/adr/0006-dual-order-channels.md)）。

### 庫存雙模型，以「劃撥」隔開

同一個 SKU 秒殺與一般通道都在賣，而**兩個真實來源必然超賣**：

```
劃撥 N 件   available -= N,  allocated += N        總量不變
秒殺進行中  只動 Redis                              MySQL 不參與
一般銷售    只動 available                          Redis 不參與
活動結束    allocated -= N,  available += 未售量     總量減少 = 實際銷量
```

順序上**一律先動 MySQL 再寫 Redis**：反過來的失敗模式是「Redis 有貨、MySQL 沒扣」，那是超賣；
正著來最壞是少賣，而少賣可以事後補救（[ADR-0008](docs/adr/0008-dual-inventory-model.md)）。

### 分層：依賴方向只能由外往內

```
編譯期    api ──▶ infrastructure ──▶ application ──▶ domain（零框架依賴）

執行期    api ──▶ application ──┬──▶ domain（純運算，零 I/O）
                                └──▶ infrastructure（Port 的實作）──▶ Redis / MySQL / Kafka / ES
```

`application` 只宣告它需要什麼（Port 介面），實作由 `infrastructure` 提供。
應用層**拿不到** `RedisTemplate` 這個類別——不是「不該用」，而是模組依賴上就用不了。
編譯期 `infrastructure` 在外面，執行期卻是 `application` 呼叫它——依賴反轉就是這個意思。

```
flash-sale-domain          純 Java。activity/ catalog/ order/ payment/ stock/ promotion/ review/ membership/ aftersales/ shipping/
flash-sale-application     Use Case 編排 + Port 介面。port/in/ port/out/ service/
flash-sale-infrastructure  出站配接器。redis/ cache/ persistence/ search/ media/ mq/ scheduler/ tracing/
flash-sale-api             HTTP 入站配接器 + 組裝根
```

「活動結束後不能下單」這條規則在 domain，因此注入固定時鐘就能測，不必起 Redis。

### 一致性：Outbox + Saga，不用 Seata

訂單落庫與領域事件寫入 `outbox_event` **在同一個資料庫交易內**，天然原子，
不需要任何分散式交易協調者（[ADR-0004](docs/adr/0004-outbox-saga-over-seata.md)）。
系統裡有兩條進 Kafka 的路，而它們不能互換：

```
  秒殺熱路徑（沒有 DB 交易）              有資料庫交易的地方（建單、付款、出貨、退貨…）
       │ 直接 publish                            │ 與資料寫入同一個交易
       ▼                                        ▼
  seckill.order.create                    ┌──────────────┐
  （12 分區，鍵=orderNo）                   │ outbox_event │
       │                                  └──────┬───────┘
       ▼                                         │ 每 1 秒，跨節點互斥
  SeckillOrderConsumer ×6 ─── 建單 ────▶  seckill.order.event（6 分區）
                                                 │
                       ┌─────────────────────────┼─────────────────┐
                       ▼                         ▼                 ▼
                 補償 / 出貨 / 銷量         積分 / 通知 / 退款    索引 / 縮圖
```

熱路徑用不了 Outbox，因為 Outbox 的全部意義是「與資料庫寫入同一個交易」，而熱路徑一次 DB 都不能碰。
所以它改用「確定投遞失敗就當場退庫」——用一個補償動作換掉一次資料庫往返。

### 讀寫並發：條件式 UPDATE 與唯一索引，不是分散式鎖

系統裡六處「同一份資料被多人同時改」（庫存扣減、券核銷、領券、評分聚合、積分兌換、銷量計入）
全部走同一個手法：**把判斷條件寫進 UPDATE 的 WHERE，或交給唯一索引**。
鎖的問題不是慢，是把並行度壓成 1，而這幾條路徑正是流量最集中的地方
（[ADR-0003](docs/adr/0003-lua-atomicity-over-distributed-lock.md)）。

聚合計數一律 `SET x = x + ?`。「SELECT 出來、在 Java 裡加、UPDATE 回去」是 read-modify-write：
兩個人同時評價，兩邊都讀到 count=10、各自寫回 11，於是一則評價從聚合上消失。

### 快照或引用：同一個問題，兩種答案

| | 存什麼 | 問的問題 |
|---|---|---|
| 訂單金額、品名、收貨地址 | **快照** | 「當初成交是什麼」 |
| 購物車 | **引用**（只存 SKU 與數量） | 「現在買要多少錢」 |

購物車存價格快照，商家調價後使用者看到舊價格卻被收新價格；訂單存地址 ID，
使用者搬家後三個月前已送達的訂單會顯示成寄到新家——那是出貨紀錄被竄改。
**把同一套規則套到兩邊，一定有一邊是錯的。**

---

## 流程

### 秒殺下單：六個步驟，由便宜到昂貴

```
① rejectIfSoldOutLocally      Caffeine 本機標記        0 次網路
② rejectIfQueueOverloaded     入場控制                 0 次網路（排程每 5 秒取樣佇列深度）
③ loadPurchasableActivity     L1 → L2 → L3            L1 命中則 0 次
④ orderNoGenerator.next()     Snowflake               0 次網路
⑤ deductStock                 Redis Lua               1 次 Redis  ← 全系統唯一強一致點
⑥ publishOrCompensate         markAccepted + Kafka    2 次 Redis + 1 次 Kafka
        │
        ▼
   202 Accepted { orderNo }
```

- **① 與 ② 在任何遠端呼叫之前**——售罄路徑實測比有庫存時還快（1,460 vs 1,244 QPS）
- **③ 之後才發號**：號碼發了卻沒扣到庫存只是浪費一個號；反過來會有扣減找不到訂單號的空窗
- **⑤ 回「重複」時直接回放既有訂單號**：同一個 `requestId` 重送永遠拿到同一張訂單
- **⑥ 確定失敗要立刻退庫，但逾時不是失敗**（見技術難點 ③）

搶購之前還有一段冷路徑：開賣前領取資格。黑名單、風險評分、驗證題全在那裡做，
熱路徑只驗一枚 HMAC 憑證——純 CPU 運算、零遠端呼叫，與 JWT 能待在熱路徑上的理由相同
（[ADR-0028](docs/adr/0028-seckill-qualification-and-risk-control.md)）。

### 一般下單：八個步驟，一個交易

```
① requestId 已有訂單？→ 直接回傳（冪等）
② 解析收貨地址 → 快照進訂單
③ 解析訂單行 → 商品名與單價快照
④ 扣庫存（MySQL 條件式 UPDATE）      ← 先於定價：庫存不足是最常見的失敗
⑤ 定價：促銷 → 券 → 運費
⑥ 核銷券                             ← 最後才做：扣庫存失敗時一起回滾，券不會被消耗
⑦ saveIfAbsent（唯一索引）
⑧ 事件寫進 outbox_event              ← 同一個交易
        ▼
   201 Created  完整訂單
```

### 消費端：一個 Outbox，九個 group

| 消費端 | 觸發事件 | 併行 | 做什麼 |
|---|---|---|---|
| `SeckillOrderConsumer` | `order.create` 主題（非 Outbox） | 6 | 非同步建單 |
| `SeckillCompensationConsumer` | `order.cancelled` | 3 | 退回 Redis 庫存 |
| `FulfillmentConsumer` | `order.paid` | 2 | 建立出貨單 |
| `ProductSalesConsumer` | `order.paid` | 2 | 累計銷量 |
| `MembershipConsumer` | `order.completed` | 2 | 發積分、更新等級 |
| `NotificationConsumer` | paid / shipped / completed / cancelled / refund | 2 | 寫入待發通知 |
| `RefundConsumer` | `refund.requested` | 1 | 執行退款 |
| `ProductIndexConsumer` | `product.index-changed` | 1 | 同步 Elasticsearch |
| `ImageVariantConsumer` | `product.image-attached` | 1 | 產生縮圖 |

每個 group 各自消費整個主題，積分掛掉不會拖到出貨。新增消費端要回答兩個問題：
「重複投遞會怎樣」與「**把歷史全部重跑一次會怎樣**」——`auto-offset-reset: earliest` 讓新 group
第一次上線會重放整個主題。答案不是「沒事」的動作（寄信、扣款、呼叫外部 API）就不該放在消費端裡。

### 狀態機：訂單只記里程碑

```
訂單  PENDING_PAYMENT ──▶ PAID ──▶ SHIPPED ──▶ COMPLETED ──▶ REFUNDED
            ├──逾時／取消──▶ CANCELLED
            └──────────────▶ FAILED

付款  PENDING ──▶ SUCCEEDED ──▶ REFUND_PENDING ──▶ PARTIALLY_REFUNDED ──▶ REFUNDED
出貨  READY ──▶ IN_TRANSIT ──▶ DELIVERED（FAILED 可重送，不是終態）
退貨  REQUESTED ──▶ APPROVED ──▶ RECEIVED ──▶ REFUNDING ──▶ REFUNDED
```

`PAID → CANCELLED` 被禁止：取消會發 `order.cancelled` 讓補償退庫，但錢已經收了，
那會製造「庫存退了、錢沒退」的路徑。已付款要退錢一律走退貨。
判準是**訂單狀態只收錄「會改變買家能做什麼」的轉折**，否則它會長成物流狀態的副本，而副本永遠慢一步。

### 補償：六道防線，一道比一道慢

| 失敗 | 誰接住 | 多久 |
|---|---|---|
| Kafka **確定**投遞失敗 | `publishOrCompensate` 當場退庫 | 毫秒 |
| Kafka 投遞**逾時** | 不退庫；訊息最終遺失時由對帳的孤兒偵測撈出 | 寬限期後 |
| 消費端重試耗盡 | DLQ + `DomainEventDeadLetterConsumer` | 秒 |
| 退款進了死信 | `RefundSettlementScheduler` 依 `REFUNDING` 狀態補送 | 60 秒一輪 |
| 使用者沒付款 | `ExpiredOrderScheduler` 關單 → `order.cancelled` → 退庫 | 30 秒一輪 |
| 以上全部失效 | `StockReconciliationService` 對帳 | 10 分鐘一輪 |

對帳核對三條恆等式，缺一不可，因為它們問的是不同的問題：

```
① 秒殺   Redis 餘量 + Σ(PENDING_PAYMENT + PAID 訂單數量) = 活動總庫存    「賣掉的有沒有被記錄」
② 一般   available = Σ 流水的 availableDelta                          「MySQL 這邊的帳對不對」
③ 劃撥   Redis 有庫存的活動，MySQL 必須有對應的劃撥額度撐著              「這批貨到底是不是我們的」
```

**自動修復預設關閉**，只修能被證明安全的方向（孤兒扣減，且已過寬限期）。
`OVERSELL_RISK` 一律人工——「自動修復」與「自動破壞」之間只隔著一個 bug。

---

## 技術難點

真正難的地方不在任何一個元件，而在分散式系統裡「失敗」有很多種長相，
每一種都要有人接住，而且接的方式不能讓另一種失敗變得更糟。
以下每一條都是實作或壓測時真的踩到的。

### ① 超賣

**原因。** 「讀餘量 → 判斷 → 扣減」三步之間任何並行都會多賣：兩個請求同時讀到餘量 1，都判斷可以買，都扣。
分散式鎖能解，但把並行度壓成 1，而這正是流量最集中的地方。

**解法。** 扣減與判斷寫進**同一支 Lua 腳本**，Redis 單執行緒保證原子，全系統只有這一個強一致點。
重複請求靠三層冪等，因為每一層都可能失效，最後一道必須是無條件成立的約束：

| 層級 | 機制 | 擋住什麼 |
|---|---|---|
| Redis Lua | `requestId → orderNo` 映射 | 使用者連點、網路重送 |
| MQ 消費端 | `saveIfAbsent` 先查後寫 | Kafka at-least-once 的重複投遞 |
| 資料庫 | `request_id` 唯一索引 | 前兩層都失效時的最終防線 |

**證據。** `RedisStockRepositoryTest`：1000 執行緒搶 100 件，成功數必須剛好 100，對著真實 Redis 跑——
這一段用 mock 等於 mock 掉唯一要驗證的東西。

### ② 熱路徑零 DB

**原因。** 資料庫的寫入能力是整個系統最低的天花板，而秒殺的定義就是所有人同時寫。
只要熱路徑碰一次資料庫，削峰就不成立——連線池會在幾秒內被佔滿。

**解法。** 熱路徑上只有 Redis 與 Kafka 各一次遠端呼叫，這是上限。身分來自 JWT（純 CPU 驗證，不查 DB）；
搶購資格是一枚 HMAC 憑證（同理）；活動資料走 L1/L2/L3 快取；訂單號用 Snowflake（本機產生）。
建單推進 Kafka，資料庫壓力由消費並行度決定，與前端流量脫鉤。

**證據。** 受理 1,471/s vs 落庫 213/s。佇列深度本身是有服務等級的：積壓超過門檻就在入口拒絕，
不讓使用者等一個永遠等不到的結果（[ADR-0023](docs/adr/0023-queue-depth-as-service-level.md)）。

### ③ 「不知道」不等於「失敗」

**原因。** Kafka 投遞用 `send().get(500ms)`，逾時就當失敗退庫。但逾時的語意是「我不想再等了」，
生產者仍在自己的 `delivery.timeout.ms` 內重試。於是：庫存退回 → 被別人買走 → 訊息稍後送達 → 訂單照樣建立。
那是真實超賣，而且對帳報出的 `OVERSELL_RISK` 方向一律不自動修——這個 bug 造成的偏差，安全網不會替我們修。

更深一層：「確定沒送出」不能靠例外型別判斷。`NotEnoughReplicasAfterAppendException` 的字面意思就是
「已經 append 到 leader 但副本不足」，ISR 恢復後那筆紀錄會變成可見，而它以 `ExecutionException` 回來。
先前設定裡的 `retries: 3` 讓這種可重試的錯誤在 500ms 之前就變成終局失敗，於是走上退庫。

**解法。** 出站埠的契約寫死：實作只在「確定沒有送出」時拋例外。`TimeoutException` 與所有
`RetriableException` 回 `PENDING` 不退庫；只有序列化、訊息過大、主題不存在這種「連 append 都不會發生」的才退。
`send-timeout` 從此只是延遲預算，不再兼任正確性判斷。訊息最終真的遺失時，那份庫存卡住等對帳的孤兒偵測——
**少賣可以事後補救，超賣不行。** 拿掉 `retries`，由 `delivery.timeout.ms` 單獨決定何時放棄，
並明訂它是孤兒寬限期的下界。

**證據。** 把 `send-timeout` 調成 1ms 實機重現：舊碼退庫後訂單照建；新碼庫存 431540 → 431539、補償 0 次，
訊息稍後落地、訂單 `PENDING_PAYMENT`（[ADR-0030](docs/adr/0030-publish-timeout-is-not-failure.md)）。

### ④ 退款的送達不能靠佇列重試

**原因。** 客服核可退款時退貨單直接寫成 `REFUNDED`，閘道呼叫在消費端非同步執行。
閘道故障時訊息重試 7.5 秒後進死信，而死信只有一行 log——**帳上退了、錢沒出去**，
且 `status = 'REFUNDED'` 裡成功與失敗的紀錄長得一模一樣，查不出來。
把重試次數調大不是答案：消費端的重試是阻塞式的，一筆重試 5 分鐘會擋住同分區後面所有人的退款，
還會撞上 `max.poll.interval.ms` 被踢出消費組。

**解法。** 「已核可」與「已到帳」拆成兩個狀態：`REFUNDING → REFUNDED`。資料庫是工作項，佇列只是快車道——
排程每分鐘掃超過寬限期的 `REFUNDING` 直接重推，不受任何重試預算限制。`REFUNDING` 沒有往失敗的轉移：
買家的貨已經退了，回頭當作沒發生的話他既沒貨也沒錢。消費端第一件事改成檢查退貨單還在不在 `REFUNDING`——
先前擋住重複退款的只有閘道的冪等鍵，那是外部系統的行為，不是我們的不變式。

同一個 PR 順手修了鏡像問題：待退款排程把 50 筆外部退款包在同一個交易裡，第 50 筆的例外會把前 49 筆的
「已退款」一起回滾——而那 49 筆的錢已經離開閘道了。

**證據。** 把一張單改回 `REFUNDING` 並把發起時間撥前一小時，下一輪排程即結算，`refunded_at` 與 `refund_started_at`
相差一小時（[ADR-0031](docs/adr/0031-refund-settlement-is-a-durable-work-item.md)）。

### ⑤ 兩套庫存不能各自認帳

**原因。** 秒殺庫存放 Redis、一般庫存放 MySQL，同一個 SKU 兩邊都在賣。預熱若直接寫「總庫存」，
Redis 一重啟（沒開持久化，依據是「庫存可重建」）就把已賣出的量抹掉，再賣一次同一批貨。
已釋放過的活動若被預熱排程重新寫回 Redis，同一批貨也會被兩條通道各賣一次。

**解法。** 以「劃撥」隔開兩邊，先動 MySQL 再寫 Redis；預熱寫的是**總庫存 − 已售出**；
已釋放過的活動不可重新預熱；釋放必須等過 `stockKeyTtlBuffer`，否則會把還在佇列裡的補償算漏。
流水記 `availableDelta` 與 `allocatedDelta` 兩個增減量——改成單一 quantity 就重建不出庫存，而重建是流水存在的唯一理由。

**證據。** 容器重建後對帳報 `OVERSELL_RISK drift +4`，那 4 件正是重啟前賣掉的。
預熱排程每分鐘補跑，釋放後 14 秒就把庫存寫回 Redis——這條路徑實際發生過
（[ADR-0008](docs/adr/0008-dual-inventory-model.md)）。

### ⑥ 收款成功絕不可被改寫成失敗

**原因。** 付款回調與逾時關單競態：使用者在第 15 分鐘按下付款，回調抵達時訂單已被關閉、庫存已退回。
此時錢**確實收了**。標記失敗會讓帳上寫「沒收到」而現實是收到的，對帳永遠對不平；
強制把訂單改回 `PAID` 則是超賣——庫存已退回並可能被別人買走。

**解法。** 如實記 `SUCCEEDED`，再轉 `REFUND_PENDING` 走退款；`payment.succeeded` 與 `payment.refund-required`
兩個事件都發，只發後者會讓下游看到一筆沒有對應收入的支出。

**證據。** `payment_callback_total{result="refund-required"}` 恆為 0 才健康；非 0 代表競態發生了，每一筆都對應一筆要退的錢。

### ⑦ 一個熱門商品能讓整條同步通道陪葬

**原因。** 所有請求搶同一個 `inventory` 列，條件式 UPDATE 取排他行鎖；等在行鎖上的請求**一直握著自己的資料庫連線**，
Hikari 連線池被排隊的人佔滿之後，後面的請求連交易都開不起來——包含要買別的商品、根本不碰這一列的請求。

**解法。** 秒殺不共用這條路，那本來就是它存在的理由。連線池耗盡改回 `503` 並標記 `retryable`，而不是 `500`：
客戶端看到「系統異常」不會重試，但這恰恰是重試就會好的錯誤。

**證據。** 同樣的程式、同樣的機器，分散 vs 集中：363 vs 29 TPS，`Innodb_row_lock_waits` 31 vs 1,444，
累計鎖等待 32 分鐘。

### ⑧ 降級路徑把系統推進放大迴圈

**原因。** 熔斷器打開時，降級方法對每一個被擋下的請求印一份完整堆疊。12 秒壓測產出 96 萬行日誌；
日誌 I/O 讓呼叫變慢 → 觸發「慢呼叫」門檻 → 熔斷器更開 → 更多請求走進降級路徑。系統自己把自己推進迴圈。
同一段程式還有第二個問題：降級方法宣告成 `private`，Resilience4j 從自己的套件反射呼叫時 `IllegalAccessException`
被包成 `UndeclaredThrowableException`——該回 503 的請求變成 500。低流量下永遠看不到：熔斷器不開，降級方法一次也不會被呼叫。

**解法。** 降級方法不印堆疊、必須 `public`。`ArchitectureTest.fallbackMethodsMustBePublic` 擋下後者。

**證據。** 修正前 51% 503 + 少量 500、962,178 行日誌；修正後全部 409、2,832 行，熔斷器完全不再打開——
那 51% 從頭到尾都是自己造成的。

### ⑨ 分區鍵選錯，六個消費者只有一個在做事

**原因。** 秒殺**依定義就只有一個活動**，用 `activityId` 當分區鍵等於單分區。

**解法。** 分區鍵要選「**同一個什麼必須有序**」——是同一張訂單，不是同一場活動。訂單號高基數、均勻散佈，
且同一張訂單的重投仍落在同一分區（[ADR-0020](docs/adr/0020-order-create-partition-key.md)）。

**證據。** 78,037 則訊息全部落在 partition 6；改 `orderNo` 後建單從 38 升到 213 TPS。

### ⑩ 一行 WARN 背後是 310 秒

**原因。** `@EntityGraph` 配上 `Limit` 時 Hibernate 無法把 LIMIT 推進 SQL，只印一行 `HHH90003004` 就把符合條件的
資料**全部**載入再於記憶體裡切。資料庫裡有 89,100 筆逾期未付款訂單，關單排程每輪只要 200 筆，
卻把全部載進 persistence context，而且整段在 `@Transactional` 裡，commit 時還要 dirty check 那 89,100 個實體。
功能完全正常，只在資料量長大後表現成「排程好像越跑越慢」。

**解法。** 兩段式：先用覆蓋索引取 ID（帶 limit），再依 ID join fetch。加一條 ArchUnit 規則擋住這個組合。

**證據。** 逾時關單（庫存的止血路徑）310 秒 → 3.5 秒；清完積壓 38 小時 → 4 小時。

### ⑪ 重試預算不能一體適用

**原因。** 所有消費端共用一份 7.5 秒的重試預算。Elasticsearch 抖動三秒，索引更新就進死信，搜尋從此停在舊資料直到有人重建；
而退款那邊 7.5 秒又太長——它有排程兜底，在佇列裡久留只會擋住後面的人。

**解法。** 預算依「失敗了要靠什麼救回來」分檔，不依重要性：另有救援管道的用快檔，只能人工重建的用慢檔，
兩檔都壓在 `max.poll.interval.ms` 之下。慢檔的錯誤處理器**刻意不註冊成 Bean**：Spring Boot 用
`ObjectProvider.getIfUnique()` 裝設 `CommonErrorHandler`，多一個同型別的 Bean 會讓預設容器安靜地失去死信行為。

**證據。** 分別對兩個容器工廠投毒，兩則都落到 DLT——預設容器沒有因為多了一個處理器而失效。

### ⑫ 撤銷令牌被自己的例外回滾

**原因。** 重用偵測的流程是「撤銷整條輪替鏈 → 拋例外拒絕請求」，但 `BusinessException` 讓外層交易回滾，
把撤銷一起還原掉——偵測到外洩卻什麼都沒撤銷。

**解法。** 撤銷經由 `RefreshTokenRevoker`，走 `REQUIRES_NEW` 獨立交易。

**證據。** 這個 bug 是實機驗證才發現的：mock 單元測試看到 `revokeFamily` 有被呼叫就會判定通過。
同一類陷阱還有 `@Transactional` 的自我呼叫——同一個 Bean 內部 `this.method()` 不經過代理，註解安靜失效，
所以 `OutboxRelayScheduler` 與 `OutboxRelayer` 刻意拆成兩個 Bean。

### ⑬ 預設金鑰在正式環境靜靜跑著

**原因。** JWT、付款回調、搶購資格三把 HMAC 金鑰的預設值就在版控裡，拿到就能偽造已登入的身分、
偽造「付款成功」的回調、自己簽發搶購資格——而偽造出來的請求驗簽成功後看起來就是正常流量，不會出現在任何指標上。
先前只是啟動時印一行警告，在正式部署的日誌洪流裡等於不存在。

**解法。** `SecretGuard` 在任一把仍是預設值時**拒絕啟動**，只有 `dev` profile 放行，與 `SnowflakeNodeIdGuard`
撞號拒絕啟動同一個立場：誤判很吵但一改設定就好，漏判會安靜到出事才發現。

**證據。** 不帶 profile 啟動，在綁定連接埠之前中止，並列出三把金鑰各自的後果與對應的環境變數。

### ⑭ Outbox 切斷了追蹤

**原因。** 事件先落 DB、排程另外搬，observation 的自動傳播在那裡就斷了——一筆秒殺在 Tempo 裡會是兩條不相干的 trace。

**解法。** `outbox_event.trace_context` 存下寫入時的 W3C `traceparent`，中繼時還原；訂單也記下建單當下的 trace id，
後台展開訂單就有「在 Tempo 查看整條建單鏈」的連結。

**證據。** 一張秒殺單在 Tempo 裡是 29 個 span：`POST /seckill/orders` → `evalsha` → `seckill.order.create send/receive`
→ `outbox.relay` → `seckill.order.event receive`（[ADR-0029](docs/adr/0029-distributed-tracing.md)）。

### ⑮ 排程與消費端的「同時跑兩次會怎樣」

**原因。** 單節點跑得好好的排程，副本一開到兩個就變成同一件事做兩次。`NotificationDeliveryScheduler` 就漏過：
撈取與標記已寄之間隔著一次真正的寄信，兩個節點會讓使用者收到兩封一樣的信，而紀錄上只有一筆。
多副本忘記改 `snowflake.node-id` 則會在同一毫秒發出完全相同的訂單號。

**解法。** 排程一律 `tryExecuteWithLock` 包住**整批做完**，而不只是撈取那一下。例外只有「每個節點各自要做一次的事」
（續自己的租約、取樣自己的佇列深度），這種反而不可以加鎖。節點編號啟動時向 Redis 宣告，撞號拒絕啟動。

### 貫穿全部的三個原則

1. **降級要看代價，不能一刀切。** 庫存服務故障 fail-closed（放行 = 無上限超賣），限流器故障 fail-open（後面還有庫存這道關）。
2. **帳目要有一個格子承認「現實可能還沒跟上」。** `REFUND_PENDING`、`REFUNDING`、投遞的 `PENDING` 都是這種格子。
   先寫上樂觀的結果，之後就查不出哪些是假的。
3. **不確定時選少賣。** 少賣可以事後補救，超賣不行。

---

## 快速開始

```bash
docker compose up -d                      # MySQL / Redis / Kafka / ES / MinIO / Prometheus / Grafana / Tempo
mvn spring-boot:run -pl flash-sale-api -Dspring-boot.run.profiles=dev
cd web && npm install && npm run dev      # http://localhost:5173
```

`dev` profile 不是可選的：三把簽章金鑰的預設值在版控裡，沒有它 `SecretGuard` 會拒絕啟動（見難點 ⑬）。
啟動時自動跑 Flyway、植入示範活動並把庫存預熱到 Redis，clone 下來即可直接搶購。
API 文件在 `http://localhost:8080/swagger-ui.html`，Grafana 在 `:3000`。

第一個管理員由設定注入，只在系統中還沒有任何管理員時生效一次——否則這份設定會變成一條永久有效的提權後門：

```bash
BOOTSTRAP_ADMIN_EMAIL=ops@example.com BOOTSTRAP_ADMIN_PASSWORD=change-me-please \
  mvn spring-boot:run -pl flash-sale-api -Dspring-boot.run.profiles=dev
```

```bash
mvn test                                             # 全部；含對著真實 Redis 的防超賣測試（需 Docker）
mvn test -pl flash-sale-domain,flash-sale-application   # 快速回饋（無需 Docker）
```

---

## 延伸閱讀

| 想知道 | 看哪裡 |
|---|---|
| 為什麼不用另一種做法 | [`docs/adr/`](docs/adr/)（每份都記著被否決的方案） |
| 撐不撐得住、踩到哪些坑 | [`docs/performance.md`](docs/performance.md)、[`benchmark/`](benchmark/) |
| 改程式前的鐵則 | [`CLAUDE.md`](CLAUDE.md)——每一條都是違反了就會出事的規則 |
| 特定任務的標準流程 | [`.claude/skills/`](.claude/skills/)（改 Lua、加 Use Case、壓測、事故排查） |
| 告警 | [`deploy/prometheus/alert-rules.yml`](deploy/prometheus/alert-rules.yml)——每一條都對應「有人要半夜起床」 |

技術棧：Java 21 · Spring Boot 3.3 · Redis 7（Lua）· Redisson · Kafka 3.7 · MySQL 8 · Flyway · Elasticsearch 8 ·
MinIO · Resilience4j · Micrometer + Prometheus + Grafana + Tempo · Testcontainers · ArchUnit · Nuxt 3 · Vue 3 · TypeScript
