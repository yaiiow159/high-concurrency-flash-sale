# 高併發分散式秒殺系統

以 Java 21 + Spring Boot 3 實作的秒殺引擎，重點在**流量削峰**、**多級快取**、
**併發控制**與**防超賣**。架構採六角架構（Ports & Adapters）的模組化單體，
分層依賴由 ArchUnit 在 CI 強制驗證。

> 本專案的每一個關鍵取捨都有對應的 [ADR](docs/adr/)。
> 程式碼說明「做了什麼」，ADR 說明「**為什麼不用另一種做法**」。

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
                     [ 非同步建單 ]  ← 資料庫壓力由消費並行度決定，與前端流量脫鉤
```

**熱路徑上沒有任何資料庫寫入**——這是削峰能成立的根本原因。

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
不需要任何分散式交易協調者。詳見 [ADR-0004](docs/adr/0004-outbox-saga-over-seata.md)。

---

## 模組結構

```
flash-sale-domain          純 Java，零框架依賴 ← ArchUnit 強制
  ├── activity/            活動聚合根、時間窗口值物件
  ├── order/               訂單聚合根、狀態機、領域事件
  ├── stock/               扣減結果值物件
  └── shared/              錯誤碼、業務例外

flash-sale-application     Use Case 編排 + Port 介面
  ├── port/in/             入站埠（Use Case 介面）
  ├── port/out/            出站埠（Repository、MQ、鎖⋯⋯）
  └── service/             Use Case 實作

flash-sale-infrastructure  出站配接器
  ├── adapter/out/redis/   Lua 扣減、Redisson 鎖、請求追蹤
  ├── adapter/out/cache/   多級快取（Decorator）、售罄標記
  ├── adapter/out/persistence/  JPA + Outbox
  ├── adapter/out/mq/      Kafka 生產者
  ├── adapter/in/mq/       Kafka 消費者、DLQ 補償
  └── scheduler/           Outbox 中繼、逾期關單、庫存預熱

flash-sale-api             HTTP 入站配接器 + 組裝根
```

依賴方向嚴格由外往內。**應用層拿不到 `RedisTemplate` 這個類別**——
不是「不該用」，而是模組依賴上就用不了。

---

## 快速開始

啟動依賴（MySQL / Redis / Kafka / Prometheus / Grafana）：

```bash
docker compose up -d
```

啟動應用：

```bash
mvn spring-boot:run -pl flash-sale-api
```

| 服務 | 位址 |
|------|------|
| API 文件 | http://localhost:8080/swagger-ui.html |
| Prometheus 指標 | http://localhost:8080/actuator/prometheus |
| Grafana 面板 | http://localhost:3000 （匿名可看） |
| Prometheus | http://localhost:9090 |

啟動時會自動執行 Flyway migration 並植入三筆示範活動（`1001`、`1002`、`1003`），
以及把庫存預熱到 Redis。clone 下來即可直接搶購，不必先改資料庫。

### 試一次搶購

先取一個開發用令牌（此端點僅在 `DEV_TOKEN_ENABLED=true` 時存在）：

```bash
curl -X POST "http://localhost:8080/api/v1/auth/dev-token?userId=1001"
```

用它發起搶購：

```bash
curl -X POST http://localhost:8080/api/v1/seckill/orders -H "Content-Type: application/json" -H "Authorization: Bearer <accessToken>" -d "{\"activityId\":1001,\"quantity\":1,\"requestId\":\"demo-req-001\"}"
```

回應 `202 Accepted`：

```json
{ "code": "00000", "data": { "orderNo": "123456789", "message": "搶購請求已受理，請稍候查詢訂單結果" } }
```

再以訂單號輪詢結果（訂單尚未落庫時回 `PROCESSING`，而非 404）：

```bash
curl http://localhost:8080/api/v1/seckill/orders/123456789 -H "Authorization: Bearer <accessToken>"
```

**重送相同的 `requestId` 會拿到同一張訂單**，庫存只扣一次。

---

## API

| 方法 | 路徑 | 認證 | 說明 |
|------|------|------|------|
| `POST` | `/api/v1/seckill/orders` | Bearer | 發起搶購，回 202 + 訂單號 |
| `GET` | `/api/v1/seckill/orders/{orderNo}` | Bearer | 查詢訂單，非同步處理中回 `PROCESSING` |
| `GET` | `/api/v1/activities` | 匿名 | 已上架活動列表 |
| `GET` | `/api/v1/activities/{id}` | 匿名 | 活動詳情（庫存餘量取自 Redis 即時值） |
| `POST` | `/api/v1/activities/{id}/warm-up` | `seckill:admin` | 手動預熱庫存（維運用） |
| `POST` | `/api/v1/auth/dev-token` | 匿名 | **僅開發環境**，取得測試令牌 |

### 認證

採 **OAuth2 Resource Server + JWT**，使用者身分取自標準的 `sub` claim
（詳見 [ADR-0005](docs/adr/0005-jwt-resource-server-over-custom-filter.md)）。

選 JWT 而非 Session 只有一個理由：**驗證是純 CPU 運算，不需要遠端呼叫**。
Session 每個請求都要讀一次 Redis，等於在熱路徑上憑空增加一次往返，
與整個削峰設計直接衝突。

由此推論出一條鐵則：**絕不可為了取得使用者資料而在認證環節查資料庫**——
一旦開始回查，JWT 的唯一優勢就消失了。

商品頁開放匿名瀏覽（不該逼使用者先登入才能看商品），
寫入操作一律需要令牌，管理端點另需 `seckill:admin` scope。

---

## 測試

```bash
mvn test                                          # 全部
mvn test -pl flash-sale-domain                    # 領域規則（毫秒級，無框架）
mvn test -pl flash-sale-infrastructure            # Redis 整合測試（需 Docker）
mvn test -pl flash-sale-api -Dtest=ArchitectureTest  # 架構約束
```

重點測試：

| 測試 | 驗證什麼 |
|------|----------|
| `RedisStockRepositoryTest` | **1000 執行緒搶 100 件庫存，成功數必須剛好 100** |
| `RedisStockRepositoryTest$Compensation` | 退庫冪等——重複退只生效一次 |
| `SeckillApplicationServiceTest` | 投遞失敗必須退庫；補償失敗不可掩蓋原始錯誤 |
| `ArchitectureTest` | 7 條分層與依賴規則，違規在 CI 就被擋下 |
| `SeckillControllerSecurityTest` | 沒帶令牌必須被擋；身分取自令牌而非請求內容 |
| `StockReconciliationServiceTest` | 偏差方向判定、孤兒寬限期、「什麼情況絕不自動修」 |

併發測試對著**真實的 Redis**（Testcontainers）執行。
這一段用 mock 等於 mock 掉唯一要驗證的東西——測試會全綠，超賣照樣發生。

---

## 庫存對帳

最終一致的系統沒有資料庫交易兜底，**偏差不會自癒，只會累積**。
補償失敗、訊息遺失、人為誤操作，每一次都在帳上留下差額，
而且沒有任何東西會主動告訴你。

`StockReconciliationService` 每 10 分鐘核對一次恆等式：

```
Redis 餘量 + Σ(PENDING_PAYMENT + PAID 訂單的數量) = 活動總庫存
```

| 偏差方向 | 判定 | 後果 | 處置 |
|---|---|---|---|
| 實際 < 應有 | `STOCK_LEAKED` | 少賣，庫存被鎖住 | 可自動修復（孤兒扣減） |
| 實際 > 應有 | `OVERSELL_RISK` | **超賣，不可逆** | 一律人工介入 |

### 孤兒扣減

最危險的一種洩漏：**庫存扣了、訂單卻不存在**。
資料庫裡沒有任何紀錄會提醒你這裡有庫存被鎖住，只有主動掃描才找得到。

判定需同時滿足兩個條件：資料庫查無此訂單號，**且**訂單號的產生時間已超過寬限期。
第二個條件不可省略——剛產生幾秒的訂單很可能只是還在 MQ 佇列裡排隊，
此時退庫，等訊息真的被消費時訂單仍會建立，那就從少賣變成了超賣。

寬限期能成立，靠的是 Snowflake 訂單號**自帶產生時間**。
這也是當初選它而非 UUID 的附帶好處：孤兒扣減沒有訂單可查，
時間資訊只能從 ID 本身取得。

### 為什麼自動修復預設關閉

因為「自動修復」與「自動破壞」之間只隔著一個 bug。
若對帳邏輯本身算錯，自動修復會拿著錯誤的結論去改動正確的資料。

只有能被證明安全的偏差才納入自動修復：孤兒扣減有明確判定依據，
且修復方向只會「歸還」庫存，不會憑空製造可賣量。
反方向的偏差一律不自動處理——下修餘量會讓進行中的合法請求無故失敗。

啟用方式：`RECONCILIATION_AUTO_REPAIR=true`。建議先觀察一段時間，
確認 `seckill_orphan_binding_total{action="detected"}` 的內容都符合預期後再開。

---

## 可觀測性

自訂業務指標（`SeckillMetrics`）：

| 指標 | 用途 |
|------|------|
| `seckill_attempt_duration_seconds` | 端到端延遲，含 P50/P95/P99 |
| `seckill_rejection_total{code}` | 拒絕原因分佈，區分「賣太好」與「壞掉了」 |
| `seckill_compensation_total{result}` | **`result="failure"` 必須恆為 0**，非零代表庫存被永久鎖住 |
| `seckill_order_persist_total{result}` | 消費端落庫速率與冪等命中數 |
| `seckill_stock_drift{activity}` | **對帳偏差，恆為 0 才健康**；> 0 代表超賣風險 |
| `seckill_orphan_binding_total{action}` | 孤兒扣減的偵測與修復結果 |

標籤只用 `activityId` 與錯誤碼，**絕不放 `userId`**——那會讓時間序列數量爆炸。

告警規則見 [`deploy/prometheus/alert-rules.yml`](deploy/prometheus/alert-rules.yml)。
每一條告警都對應一個「有人要在半夜起床處理」的狀況；
會響但沒人需要行動的告警，只會訓練團隊忽略所有告警。

---

## 演進規劃

本專案正朝完整電商平台演進，路線圖與架構主張記錄在 [`docs/roadmap/`](docs/roadmap/)。

核心主張是**秒殺是特例通道，不是骨幹**——現有架構的每個設計都建立在
「流量極大、庫存極小、99.9% 請求注定失敗」這個前提上，而一般電商的流量特徵完全相反。
因此規劃採**雙下單通道**：一般下單走同步交易一致，秒殺走既有的非同步削峰。

| 文件 | 內容 |
|------|------|
| [總覽](docs/roadmap/README.md) | 現況盤點、五條核心架構主張、P0–P5 路線圖、風險與反模式 |
| [界限脈絡](docs/roadmap/bounded-contexts.md) | 12 個脈絡的切分、跨脈絡通訊規則、模組演進、資料模型 |
| [前端](docs/roadmap/frontend.md) | 渲染策略、秒殺頁的削峰第 0 層、認證流程、契約管理 |

---

## 給 AI 協作者的自動化配置

本專案的 `.claude/` 目錄把架構約束與操作流程沉澱成可執行的資產，
讓 AI 協作時不必每次重述規則：

| 類型 | 用途 | 位置 |
|------|------|------|
| **Instructions** | 每次對話都載入的鐵則（分層、熱路徑禁忌、冪等要求） | [`CLAUDE.md`](CLAUDE.md) |
| **Skills** | 特定任務的標準流程（改 Lua、加 Use Case、加領域事件、壓測、事故排查） | [`.claude/skills/`](.claude/skills/) |
| **Hooks** | 檔案存檔當下的即時守門，比等 CI 快 | [`.claude/hooks/`](.claude/hooks/) |
| **Agents** | 專職審查者（併發正確性、熱路徑效能） | [`.claude/agents/`](.claude/agents/) |

設計原則與取捨說明見 [`.claude/README.md`](.claude/README.md)。

---

## 技術棧

Java 21 · Spring Boot 3.3 · Redis 7（Lua）· Redisson · Kafka 3.7 ·
MySQL 8 · Flyway · Resilience4j · Micrometer + Prometheus + Grafana ·
Testcontainers · ArchUnit
