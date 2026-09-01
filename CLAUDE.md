# 高併發秒殺系統 — 專案協作指南

這份文件是**每次對話都會載入**的內容，因此只放「違反了就會出事」的鐵則。
流程性的知識放在 `.claude/skills/`，需要時才載入。

---

## 一句話理解這個系統

用極少的庫存承接極大的流量。設計重點不是讓成功的請求更快，
而是**讓注定失敗的 99.9% 請求以最低成本被擋下**。

---

## 不可違反的規則

### 1. 分層依賴只能由外往內

```
api → infrastructure → application → domain
```

- **領域層零框架依賴**——不得出現 `org.springframework`、`jakarta.persistence`、
  `com.fasterxml.jackson`、`org.apache.kafka`、`org.redisson`、`io.micrometer`
- **應用層只認得 Port 介面**——不得 import `com.flashsale.infrastructure.*`、
  `org.springframework.data.*`、`org.springframework.web.*`
- 應用層僅允許 `spring-context`（`@Service`）與 `spring-tx`（`@Transactional`）

違規會被 `ArchitectureTest` 擋下，也會被 PostToolUse hook 在存檔當下擋下。
**不要為了通過測試而放寬 ArchUnit 規則**——規則被放寬過一次，就再也不會收回去。

### 2. 熱路徑（`SeckillApplicationService.attempt`）禁止事項

這條路徑上每多一次網路往返，就多一份尖峰時的延遲與故障面。

- ❌ **禁止任何資料庫讀寫**（`@Transactional`、Repository 的 DB 實作）
- ❌ **禁止用分散式鎖包住庫存扣減**——Lua 已保證原子性，加鎖只會把並行度壓成 1
- ❌ **禁止新增同步遠端呼叫**——目前只有 Redis 與 Kafka 各一次，這是上限
- ❌ **禁止在迴圈中呼叫 Redis**——要批次就寫進 Lua
- ❌ **禁止為了取得使用者資料而查資料庫**——JWT 能待在熱路徑上的唯一理由
  就是驗證純 CPU 運算、零遠端呼叫。需要的資訊必須全放在 claim 裡

需要新增遠端呼叫時，先問：能不能移到 MQ 消費端的慢車道？

### 3. 庫存的一致性規則

- 扣減與退回**只能透過 Lua 腳本**。看到 `redis.get()` 後接 `redis.decr()` 的程式碼，
  那就是超賣的來源，必須改寫成 Lua
- **Redis 故障時不可降級放行**（fail-closed）。放行等於無上限超賣。
  對照組：限流器 Redis 故障時**應該**放行（fail-open），因為後面還有庫存這道關卡。
  降級策略要看「這道防線失守會付出什麼代價」，不能一刀切
- 退庫**必須冪等**。補償排程、DLQ 消費端、同步補償三個路徑可能同時對同一筆訂單發起退庫

### 4. 冪等是三層，不是一層

| 層級 | 機制 | 位置 |
|------|------|------|
| Redis | `requestId → orderNo` 映射 | `seckill_deduct.lua` |
| 消費端 | `saveIfAbsent` | `OrderCreationService` |
| 資料庫 | `request_id` 唯一索引 | `V1__init_schema.sql` |

**新增任何 MQ 消費端時，冪等是必答題**。Outbox 是至少一次語意，重複投遞是常態不是異常。

### 5. Lua 腳本與 Java 列舉是一組契約

`seckill_deduct.lua` 的回傳碼必須與 `StockDeductionOutcome` 完全對應。
改了任何一邊，**另一邊與 `RedisStockRepositoryTest` 都必須同步更新**。
（`.claude/skills/seckill-lua-script/` 有完整流程）

**扣減憑證的格式也是契約**：`orderNo|userId|quantity`，由 `StockBindingCodec` 編解碼。
不可簡化成只存 `orderNo`——對帳發現孤兒扣減時沒有訂單可查數量，
憑證若不自帶，那筆洩漏就變成偵測得到卻修不掉的死結。

### 6. `@Transactional` 不可自我呼叫

Spring 的交易是動態代理，同一個 Bean 內部呼叫 `this.method()` **不會經過代理**，
註解會安靜失效且沒有任何錯誤訊息。

參考 `OutboxRelayScheduler` 與 `OutboxRelayer` 的拆分——
排程觸發器與交易方法刻意分成兩個 Bean，就是為了避開這個陷阱。

### 7. 身分一律來自令牌，不可來自請求內容

使用者 ID 取自 JWT 的 `sub` claim（`@CurrentUser` 注入），
**不得**從 `X-User-Id` 這類標頭或請求體讀取——那等於讓呼叫端自己宣告身分。

這不只是認證問題：單一使用者限流若以呼叫端自填的 ID 為維度，
攻擊者每次換一個號碼就能完全繞過，那道限流等於不存在。

管理端點（預熱、對帳觸發）需要 `seckill:admin` scope。
新增端點時預設就是「需要認證」——`SecurityConfig` 以 `anyRequest().authenticated()` 收尾，
要開放必須明確加進放行清單。**放行清單逐一列出路徑，不可用 `/**` 一次放行**——
那會連還沒寫的端點也一起開放。

### 7-1. 撤銷令牌必須走獨立交易

重用偵測的流程是「撤銷整條輪替鏈 → 拋例外拒絕請求」。
但 `BusinessException` 會讓外層交易回滾，**把撤銷一起還原掉**——
偵測到外洩卻什麼都沒撤銷。

因此撤銷一律經由 `RefreshTokenRevoker`（`REQUIRES_NEW`），
與 `OutboxRelayer` 拆分同理。**這個 bug 是實機驗證才發現的，
mock 單元測試看到 `revokeFamily` 有被呼叫就會判定通過。**

### 8. 對帳的自動修復預設關閉

`StockReconciliationService` 只在能被證明安全的情況下才退庫：
**孤兒扣減**（訂單不存在 + 已超過寬限期）。

- 寬限期**必須明顯長於**付款期限與 MQ 最大重試時間。設太短會把還在佇列
  排隊的請求誤判為孤兒而退庫，等訊息被消費時就從少賣變成超賣
- `OVERSELL_RISK` 方向**一律不自動處理**。下修餘量會讓進行中的合法請求無故失敗
- 改動對帳邏輯時，`StockReconciliationServiceTest` 的「什麼情況絕不自動修」
  那幾條測試不可放寬

### 9. 時間一律注入 `Clock`

不得直接呼叫 `Instant.now()` 或 `System.currentTimeMillis()`。
理由是可測試性：驗證「活動結束後不能下單」應該注入固定時鐘，
而不是改系統時間或讓測試 sleep。

---

## 程式碼慣例

- **註解寫「為什麼」，不寫「做了什麼」**。`// 迴圈遍歷訂單` 是雜訊；
  `// 逐筆處理而非批次 UPDATE，因為批次會繞過聚合根的狀態機` 才有價值
- 註解與 Javadoc 使用**繁體中文**，識別符號使用英文
- 領域層用 record 與不可變類別；基礎設施層可用 JPA Entity 的可變欄位
- 值物件優先於裸 `String`／`Long`（見 `OrderNo`）——讓編譯器擋下參數傳錯
- 業務例外一律用 `BusinessException` + `ErrorCode`，**不要新增自訂例外類別**
- 新增錯誤碼時同步確認 `GlobalExceptionHandler.STATUS_MAPPING` 是否需要對應

---

## 常用指令

```bash
mvn -q compile                                        # 快速編譯檢查
mvn test -pl flash-sale-domain,flash-sale-application # 快速回饋（無需 Docker）
mvn test -pl flash-sale-infrastructure                # Redis 整合測試（需 Docker）
mvn test -pl flash-sale-api -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
docker compose up -d                                  # 啟動依賴
curl -X POST localhost:8080/api/v1/auth/register -H "Content-Type: application/json" -d '{"email":"a@b.com","password":"password123","displayName":"A"}'
curl -X POST localhost:8080/api/v1/auth/login    -H "Content-Type: application/json" -d '{"email":"a@b.com","password":"password123"}'
mvn spring-boot:run -pl flash-sale-api                # 啟動應用
```

**改動 Lua 腳本、`StockRepository`、或任何併發相關程式碼後，
必須跑 `RedisStockRepositoryTest`**——這是防超賣的唯一實證。

---

## 決策已經做過的事，不要重新發明

以下取捨都有 ADR 記錄理由，修改前請先讀：

| 想做的改動 | 先讀 |
|------------|------|
| 「庫存應該放資料庫才對」 | [ADR-0002](docs/adr/0002-stock-in-redis-not-database.md) |
| 「這裡應該加分散式鎖」 | [ADR-0003](docs/adr/0003-lua-atomicity-over-distributed-lock.md) |
| 「應該用 Seata 做分散式交易」 | [ADR-0004](docs/adr/0004-outbox-saga-over-seata.md) |
| 「應該拆成微服務」 | [ADR-0001](docs/adr/0001-modular-monolith-hexagonal.md) |
| 「認證改用 Session 比較簡單」 | [ADR-0005](docs/adr/0005-jwt-resource-server-over-custom-filter.md) |

若確實有新的理由推翻既有決策，**請新增一份 ADR 說明前提變化**，而不是直接改程式碼。
