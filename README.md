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

```bash
curl -X POST http://localhost:8080/api/v1/seckill/orders -H "Content-Type: application/json" -H "X-User-Id: 1001" -d "{\"activityId\":1001,\"quantity\":1,\"requestId\":\"demo-req-001\"}"
```

回應 `202 Accepted`：

```json
{ "code": "00000", "data": { "orderNo": "123456789", "message": "搶購請求已受理，請稍候查詢訂單結果" } }
```

再以訂單號輪詢結果（訂單尚未落庫時回 `PROCESSING`，而非 404）：

```bash
curl http://localhost:8080/api/v1/seckill/orders/123456789 -H "X-User-Id: 1001"
```

**重送相同的 `requestId` 會拿到同一張訂單**，庫存只扣一次。

---

## API

| 方法 | 路徑 | 說明 |
|------|------|------|
| `POST` | `/api/v1/seckill/orders` | 發起搶購，回 202 + 訂單號 |
| `GET` | `/api/v1/seckill/orders/{orderNo}` | 查詢訂單，非同步處理中回 `PROCESSING` |
| `GET` | `/api/v1/activities` | 已上架活動列表 |
| `GET` | `/api/v1/activities/{id}` | 活動詳情（庫存餘量取自 Redis 即時值） |
| `POST` | `/api/v1/activities/{id}/warm-up` | 手動預熱庫存（維運用） |

身分以 `X-User-Id` 標頭傳遞——這是為了讓專案聚焦在併發主題而簡化的做法，
正式環境應換成 JWT 或 OAuth2 Resource Server。

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

併發測試對著**真實的 Redis**（Testcontainers）執行。
這一段用 mock 等於 mock 掉唯一要驗證的東西——測試會全綠，超賣照樣發生。

---

## 可觀測性

自訂業務指標（`SeckillMetrics`）：

| 指標 | 用途 |
|------|------|
| `seckill_attempt_duration_seconds` | 端到端延遲，含 P50/P95/P99 |
| `seckill_rejection_total{code}` | 拒絕原因分佈，區分「賣太好」與「壞掉了」 |
| `seckill_compensation_total{result}` | **`result="failure"` 必須恆為 0**，非零代表庫存被永久鎖住 |
| `seckill_order_persist_total{result}` | 消費端落庫速率與冪等命中數 |

標籤只用 `activityId` 與錯誤碼，**絕不放 `userId`**——那會讓時間序列數量爆炸。

告警規則見 [`deploy/prometheus/alert-rules.yml`](deploy/prometheus/alert-rules.yml)。
每一條告警都對應一個「有人要在半夜起床處理」的狀況；
會響但沒人需要行動的告警，只會訓練團隊忽略所有告警。

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
