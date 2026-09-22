# 高併發分散式秒殺系統

Java 21 + Spring Boot 3 的秒殺引擎，並在其上長成一個完整電商。
重點在**流量削峰**、**併發控制**與**防超賣**；六角架構的模組化單體，分層依賴由 ArchUnit 強制驗證。

> 每個關鍵取捨都有 [ADR](docs/adr/)：程式碼說「做了什麼」，ADR 說「**為什麼不用另一種做法**」。
> 完整壓測數據在 [`docs/performance.md`](docs/performance.md)，改程式前的鐵則在 [`CLAUDE.md`](CLAUDE.md)。

---

## 架構設計

**秒殺的本質是用極少的庫存承接極大的流量。** 1000 件商品湧入百萬請求，99.9% 注定失敗——
設計重點不是讓成功的請求更快，而是**讓注定失敗的請求以最低成本被擋下**。

```
                          瀏覽器 / 行動裝置
                                │
                    ┌───────────▼───────────┐
                    │  Nuxt 3（SSR + ISR）   │  漏斗第 0 層：靜態頁走 CDN，庫存數字另走輕量請求
                    │  BFF：refresh token   │
                    │  收進 httpOnly cookie  │
                    └───────────┬───────────┘
                                │ /api/v1/**（JWT）
                    ┌───────────▼───────────┐
                    │   Spring Boot 單體      │  api → infrastructure → application → domain
                    │   ┌─────────────────┐ │
                    │   │ 秒殺熱路徑（零 DB）│ │  售罄標記 → 入場控制 → 多級快取 → Redis Lua → Kafka → 202
                    │   └─────────────────┘ │
                    │   ┌─────────────────┐ │
                    │   │ 一般交易通道     │ │  MySQL 條件式 UPDATE + Outbox，同一交易 → 201
                    │   └─────────────────┘ │
                    │   9 個 Kafka 消費端    │  建單／補償／出貨／積分／通知／退款／索引／縮圖／銷量
                    │   12 個背景排程        │  Outbox 中繼、逾時關單、退款補送、對帳、預熱…
                    └──┬────┬────┬────┬────┘
             ┌─────────▼┐ ┌─▼──┐ ┌▼───┐ ┌▼──────────────┐
             │  Redis   │ │MySQL│ │Kafka│ │ Elasticsearch │   MinIO（商品圖）
             │ 秒殺庫存  │ │真實 │ │佇列 │ │ 搜尋讀模型     │
             │ 多級快取  │ │來源 │ │+DLT │ │               │
             └──────────┘ └────┘ └────┘ └───────────────┘
                       ▲ Prometheus + Grafana + Tempo，整條建單鏈同一個 trace id
```

### 削峰漏斗：四層過濾，一層比一層貴

| 層 | 機制 | 成本 | 擋下什麼 |
|---|---|---|---|
| ① 本機售罄標記 | Caffeine | 奈秒，零網路 | 售罄後的洪峰（99.9% 的請求） |
| ② 多級快取 + 規則校驗 | L1 Caffeine → L2 Redis → L3 MySQL | 多數命中本機 | 活動未開始、限購、資格無效 |
| ③ Redis Lua 原子扣減 | 單支腳本，判斷與扣減一起 | 一次 RTT | 超賣——全系統唯一強一致點 |
| ④ Kafka 投遞 | 建單移出請求鏈路 | 一次 RTT | 資料庫壓力與前端流量脫鉤 |

**熱路徑上沒有任何資料庫讀寫**——身分來自 JWT、資格是 HMAC 憑證、訂單號是 Snowflake，全部純 CPU。
這是削峰能成立的根本原因：實測受理 1,471/s、落庫 213/s，多出來的排在 Kafka 裡。

### 四個結構性決定

| 決定 | 內容 | 為什麼 |
|---|---|---|
| **兩條下單通道不共用程式** | 秒殺：Redis Lua + Kafka，`202`。一般：MySQL 條件式 UPDATE + Outbox，`201` | 「所有人搶同一件」與「數萬 SKU 各自獨立」是不同的問題；統一成一個狀態碼就是對其中一邊說謊（[ADR-0006](docs/adr/0006-dual-order-channels.md)） |
| **庫存雙模型，以劃撥隔開** | 秒殺額度從一般庫存切出，一律先動 MySQL 再寫 Redis；預熱寫「總量 − 已售出」 | 兩個真實來源必然超賣；反向操作的失敗模式是超賣，正向最壞是少賣（[ADR-0008](docs/adr/0008-dual-inventory-model.md)） |
| **Outbox + Saga，不用 Seata** | 訂單與領域事件同一個資料庫交易；熱路徑例外——確定投遞失敗就當場退庫 | Outbox 的全部意義是「與 DB 寫入同交易」，而熱路徑一次 DB 都不能碰（[ADR-0004](docs/adr/0004-outbox-saga-over-seata.md)） |
| **條件式 UPDATE 與唯一索引，不用分散式鎖** | 六處併發修改（庫存、券、評分、積分、銷量…）全部把判斷寫進 WHERE 或交給唯一索引 | 鎖的問題不是慢，是把並行度壓成 1，而這幾條路徑正是流量最集中處（[ADR-0003](docs/adr/0003-lua-atomicity-over-distributed-lock.md)） |

---

## 使用技術

| 層面 | 技術 |
|---|---|
| 語言 / 框架 | Java 21 · Spring Boot 3.3 · Spring Security（JWT resource server）· Spring Data JPA · Flyway |
| 庫存 / 快取 / 鎖 | Redis 7（Lua 腳本）· Redisson（分散式鎖、看門狗）· Caffeine |
| 訊息 | Kafka 3.7（Outbox 中繼、DLT、分檔重試預算） |
| 資料 | MySQL 8（keyset 分頁、覆蓋索引）· Elasticsearch 8（搜尋讀模型）· MinIO（商品圖，預簽名直傳） |
| 韌性 | Resilience4j（熔斷、限流）· 入場控制（佇列深度作為服務水準） |
| 可觀測 | Micrometer · Prometheus · Grafana · Tempo（OTLP，Outbox 跨交易保留 trace） |
| 架構守門 | 六角架構 · ArchUnit（分層依賴、`@EntityGraph`+`Limit`、fallback 可見性）· Testcontainers |
| 前端 | Nuxt 3（逐頁 SSR / ISR）· Vue 3 · TypeScript · Pinia · Tailwind · BFF（refresh token 進 httpOnly cookie） |

---

## 效能實測

單機本機實測（MySQL、Redis、Kafka、ES、JVM 與壓測工具擠在同一台），絕對值被壓低，
**有意義的是比例與曲線形狀**。完整表格與重跑方式見 [`docs/performance.md`](docs/performance.md)。

### 秒殺熱路徑（50 萬庫存，全部 `202`，零 4xx/5xx）

| 併發 | TPS | p50 | p99 |
|---|---|---|---|
| 50 | 1,114 | 77ms | 194ms |
| 200 | 1,244 | 164ms | 254ms |
| 500 | 1,471 | 334ms | 640ms |

| | 速率 | 決定於 |
|---|---|---|
| 前端受理 | **1,471 /s** | 一次 Redis + 一次 Kafka |
| 消費端落庫 | **213 /s** | 消費並行度 × 資料庫寫入能力 |

**約 7:1** ——不加大資料庫，只是不讓它決定使用者等多久。

### 售罄之後（漏斗第 ① 層，200 併發）

| 情境 | QPS | p50 | p99 | 狀態碼 |
|---|---|---|---|---|
| 有庫存 | 1,244 | 164ms | 254ms | 全部 202 |
| **已售罄** | **1,460** | **141ms** | **215ms** | 全部 409 |

售罄後比有庫存時更快：連 Redis 都不碰，本機標記直接回絕。

### 讀路徑（50 併發）

| 情境 | QPS | p50 | p99 |
|---|---|---|---|
| 商品詳情（隨機 5 萬筆） | 1,295 | 75ms | 162ms |
| 商品列表（第一頁） | 1,108 | 85ms | 157ms |
| **商品列表（深分頁 offset 4 萬）** | **1,153** | **79ms** | **156ms** |
| 搜尋（Elasticsearch） | 1,422 | 62ms | 112ms |
| 進行中的活動 | 3,032 | 66ms | 146ms |
| 前端 SSR `/products/{id}`（ISR） | 1,354 | 35ms | 55ms |

深分頁與第一頁幾乎一樣快：keyset 複合游標直接定位，不掃四萬列（[ADR-0021](docs/adr/0021-keyset-pagination.md)）。

### 一般下單（同步交易通道）

| 情境 | 併發 | TPS | p99 |
|---|---|---|---|
| 分散（10 萬 SKU 隨機） | 100 | **363** | 691ms |
| 集中（全部搶同一 SKU） | 100 | **27** | 6,556ms |

同樣的程式，只差在買不買同一件：**掉 12 倍**，`Innodb_row_lock_waits` 31 → 1,444。
這正是秒殺不能共用這條路的原因。

---

## 技術難點與解法

每一條都是實作或壓測時真的踩到的。完整推導、失敗案例與數據在對應的 ADR 與 [`docs/performance.md`](docs/performance.md)。

| # | 難點 | 原因 | 解法 | 證據 |
|---|---|---|---|---|
| ① | **超賣** | 「讀 → 判斷 → 扣」三步之間任何並行都多賣；分散式鎖把並行度壓成 1 | 判斷與扣減寫進**同一支 Lua**；冪等三層（Redis `requestId` 映射 → 消費端 `saveIfAbsent` → DB 唯一索引） | `RedisStockRepositoryTest`：1000 執行緒搶 100 件，成功數剛好 100，對真實 Redis 跑 |
| ② | **熱路徑零 DB** | 資料庫寫入是最低的天花板，碰一次連線池就在幾秒內被佔滿 | 熱路徑只有 Redis + Kafka 各一次；JWT、HMAC 資格、Snowflake 全是本機運算；佇列深度超過門檻就在入口拒絕 | 受理 1,471/s vs 落庫 213/s（[ADR-0023](docs/adr/0023-queue-depth-as-service-level.md)） |
| ③ | **「不知道」≠「失敗」** | Kafka 投遞逾時就退庫，但生產者仍在重試：庫存被別人買走、訊息稍後送達、訂單照建——真實超賣 | 出站埠契約：只有「確定沒送出」才拋例外；逾時與 `RetriableException` 回 `PENDING` 不退庫，交給孤兒對帳。**少賣可補救，超賣不行** | `send-timeout` 調 1ms 重現：舊碼退庫後訂單照建；新碼補償 0 次（[ADR-0030](docs/adr/0030-publish-timeout-is-not-failure.md)） |
| ④ | **退款送達不能靠佇列重試** | 核可即寫 `REFUNDED`，閘道故障進死信後**帳上退了、錢沒出去**，且與成功紀錄長得一樣 | `REFUNDING → REFUNDED` 拆兩段，DB 是工作項、佇列只是快車道；排程掃超時的 `REFUNDING` 直接重推 | 撥前一小時後下一輪即結算（[ADR-0031](docs/adr/0031-refund-settlement-is-a-durable-work-item.md)） |
| ⑤ | **兩套庫存各自認帳** | 預熱直接寫總庫存，Redis 一重啟就把已賣出的量抹掉再賣一次；釋放後的活動被預熱排程寫回 | 劃撥隔開、先 MySQL 再 Redis；預熱寫「總量 − 已售出」；已釋放不可重預熱；流水記兩個增減量 | 容器重建後對帳報 `OVERSELL_RISK drift +4`，正是重啟前賣掉的 4 件（[ADR-0008](docs/adr/0008-dual-inventory-model.md)） |
| ⑥ | **收款成功被改寫成失敗** | 付款回調與逾時關單競態：錢確實收了，訂單卻已關、庫存已退 | 如實記 `SUCCEEDED` 再轉 `REFUND_PENDING`；兩個事件都發。標失敗是帳對不平，改回 `PAID` 是超賣 | `payment_callback_total{result="refund-required"}` 應恆為 0 |
| ⑦ | **一個熱門商品讓整條同步通道陪葬** | 等行鎖的請求握著連線，Hikari 被佔滿後買別的商品也開不了交易 | 秒殺不共用這條路；連線池耗盡回 `503` + `retryable` 而非 `500` | 分散 vs 集中：363 vs 29 TPS，鎖等待累計 32 分鐘 |
| ⑧ | **降級路徑推系統進放大迴圈** | 熔斷降級對每個請求印完整堆疊，日誌 I/O 讓呼叫更慢 → 熔斷更開；降級方法 `private` 被反射呼叫失敗，503 變 500 | 降級不印堆疊、必須 `public`；ArchUnit 擋下後者 | 修正前 51% 503、96 萬行日誌；修正後全部 409、2,832 行，熔斷器不再打開 |
| ⑨ | **分區鍵選錯** | 秒殺依定義只有一個活動，用 `activityId` 等於單分區，六個消費者只有一個在做事 | 分區鍵選「同一個什麼必須有序」——是同一張訂單 | 78,037 則全落 partition 6；改 `orderNo` 後 38 → 213 TPS（[ADR-0020](docs/adr/0020-order-create-partition-key.md)） |
| ⑩ | **一行 WARN 背後是 310 秒** | `@EntityGraph` + `Limit` 讓 Hibernate 把 89,100 筆全載進記憶體再切 200 筆，commit 還要 dirty check | 兩段式：覆蓋索引取 ID 再 join fetch；ArchUnit 擋住這個組合 | 逾時關單 310s → 3.5s，清完積壓 38h → 4h |
| ⑪ | **重試預算不能一體適用** | 一份 7.5 秒預算：ES 抖動就進死信要人工重建；退款那邊又太長會擋住同分區 | 依「失敗了靠什麼救回來」分快慢兩檔，皆壓在 `max.poll.interval.ms` 下；慢檔處理器刻意不註冊成 Bean | 分別對兩個容器投毒，兩則都落到 DLT |
| ⑫ | **撤銷令牌被自己的例外回滾** | 重用偵測「撤銷輪替鏈 → 拋例外」，例外讓外層交易把撤銷一起還原 | 撤銷走 `REQUIRES_NEW` 獨立交易 | mock 測試看到 `revokeFamily` 被呼叫就過，實機才發現 |
| ⑬ | **預設金鑰在正式環境靜靜跑著** | 三把 HMAC 金鑰預設值在版控裡，偽造的請求驗簽成功後就是正常流量，不出現在任何指標 | `SecretGuard` 拒絕啟動，只有 `dev` profile 放行 | 不帶 profile 啟動在綁埠前中止 |
| ⑭ | **Outbox 切斷追蹤** | 事件先落 DB 再由排程搬，一筆秒殺在 Tempo 裡是兩條不相干的 trace | `outbox_event.trace_context` 存 W3C `traceparent`，中繼時還原 | 一張秒殺單 29 個 span 連成一條（[ADR-0029](docs/adr/0029-distributed-tracing.md)） |
| ⑮ | **排程「同時跑兩次會怎樣」** | 副本開到兩個，寄信排程讓使用者收到兩封信而紀錄只有一筆；忘改 `snowflake.node-id` 同毫秒撞訂單號 | 排程用分散式鎖包住**整批做完**；節點編號啟動時向 Redis 宣告，撞號拒絕啟動 | `NotificationDeliveryScheduler` 就漏過這一條 |

**貫穿全部的三個原則：** 降級要看代價（庫存 fail-closed、限流 fail-open）；
帳目要有一個格子承認「現實可能還沒跟上」（`REFUND_PENDING`、`REFUNDING`、投遞 `PENDING`）；
不確定時選少賣。

---

## 快速開始

```bash
docker compose up -d                                                  # MySQL / Redis / Kafka / ES / MinIO / Prometheus / Grafana / Tempo
mvn spring-boot:run -pl flash-sale-api -Dspring-boot.run.profiles=dev # dev 必填：預設金鑰只在 dev 放行
cd web && npm install && npm run dev                                  # http://localhost:5173
```

啟動時自動跑 Flyway、植入示範活動並預熱庫存，clone 下來即可搶購。
API 文件 `:8080/swagger-ui.html`，Grafana `:3000`。

```bash
mvn test                                              # 全部，含對真實 Redis 的防超賣測試（需 Docker）
mvn test -pl flash-sale-domain,flash-sale-application # 快速回饋（無需 Docker）
```

| 想知道 | 看哪裡 |
|---|---|
| 為什麼不用另一種做法 | [`docs/adr/`](docs/adr/) |
| 完整壓測數據與踩到的坑 | [`docs/performance.md`](docs/performance.md)、[`benchmark/`](benchmark/) |
| 改程式前的鐵則 | [`CLAUDE.md`](CLAUDE.md) |
