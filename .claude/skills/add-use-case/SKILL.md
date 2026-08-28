---
name: add-use-case
description: 在六角架構下新增業務功能（Use Case）的標準流程。當要新增 API 端點、新增業務操作、或新增需要跨越分層的功能時使用。涵蓋 Port 定義順序、各層職責邊界、交易邊界的擺放位置、以及對應的測試策略。
---

# 新增 Use Case

六角架構的新增順序**由內往外**：先定義業務意圖，再決定技術實作。
反過來（先寫 Controller 再往下推）幾乎必然會讓技術細節滲進業務模型。

---

## 步驟

### 1. 領域層：這個操作屬於哪個聚合根？

先問：**這條規則能不能只靠聚合根自己的狀態判斷？**

能 → 寫成聚合根的方法（例如 `SeckillActivity.ensurePurchasableAt`）
不能（需要查詢其他資料） → 留到應用層編排

新增狀態轉移時，同步更新 `OrderStatus.ALLOWED_TRANSITIONS`——
合法轉移集中宣告在一處，就不會有人在某個 service 裡偷偷放行一個非法轉移。

領域層的方法**傾向拋例外而非回傳布林**：
呼叫端無法忽略失敗原因，且前端能拿到精確的錯誤碼區分文案。

### 2. 應用層：定義入站埠

`application/port/in/XxxUseCase.java`

介面的 Javadoc 要寫清楚**成功代表什麼、不代表什麼**。
例如 `SeckillUseCase.attempt` 明確寫著「成功僅代表庫存預扣成功且訊息已投遞，
**不代表訂單已落庫**」——這個區別是整個非同步設計的核心。

輸入用 Command record（帶建構期驗證），輸出用 DTO record。
**不要讓 Controller 直接吃領域物件**：內部模型應該能自由重構，
一旦外洩成 API 契約就動不了了。

### 3. 應用層：需要哪些出站埠？

先寫**介面**，不管實作。介面的命名要用業務語彙，不要用技術語彙：

- ✅ `StockRepository.deduct(...)` — 說的是「扣減庫存」
- ❌ `RedisLuaExecutor.eval(...)` — 說的是「執行 Lua」，技術細節洩漏了

新增出站埠時，Javadoc 要寫明**實作必須保證什麼**
（原子性？冪等？失敗時該拋還是該回傳？）。
這是介面對實作提出的契約，不寫清楚就等著實作者各自解讀。

### 4. 應用層：實作 Use Case

```java
@Service
public class XxxApplicationService implements XxxUseCase {
    // 依賴一律是 Port 介面，透過建構子注入
}
```

**交易邊界的判斷**：

| 情況 | 是否加 `@Transactional` |
|------|------------------------|
| 熱路徑（搶購） | ❌ 不加。這條路徑沒有 DB 寫入，加了只是白白佔住連線 |
| MQ 消費端建單 | ✅ 加。訂單與 Outbox 事件必須原子 |
| 查詢 | ✅ 加 `readOnly = true` |
| 需要退 Redis 庫存 | ❌ **絕不可**放進交易。Redis 無法回滾，交易失敗會造出幽靈庫存 |

最後一條是最容易踩的：**跨資源的補償必須放在交易外，由事件驅動**。

### 5. 基礎設施層：實作出站埠

- Redis → `adapter/out/redis/`
- 資料庫 → `adapter/out/persistence/`
- MQ → `adapter/out/mq/`

**框架例外不可外洩**。`DataIntegrityViolationException`、`DataAccessException`
必須在這一層被翻譯成業務語意（回傳 `Optional.empty()`）或 `BusinessException`。
參考 `JpaOrderRepository.saveIfAbsent` 的寫法。

### 6. API 層：入站配接器

Controller 只做四件事：**取出身分、驗證格式、委派、決定 HTTP 語意**。

- 請求體用獨立的 Request record（帶 Bean Validation），不要直接收 Command
- `userId` 一律從認證脈絡取得，**不可從請求體讀取**（否則能冒用他人身分）
- HTTP 狀態碼要誠實：非同步受理用 `202`，不要用 `201`
- 新增錯誤碼時檢查 `GlobalExceptionHandler.STATUS_MAPPING`

### 7. 測試

| 層 | 測試方式 | 需要什麼 |
|----|----------|----------|
| 領域 | 純單元測試 | 什麼都不需要，毫秒級 |
| 應用 | Mockito mock 所有 Port | 不需要 Spring／Docker |
| 基礎設施 | Testcontainers 對真實中介軟體 | 需要 Docker |
| 架構 | 自動被 `ArchitectureTest` 涵蓋 | — |

**應用層測試不該出現 `@SpringBootTest`**。若你發現非得啟動 Spring 才測得動，
那代表有技術細節漏進了應用層——這時該修的是設計，不是測試。

---

## 檢查清單

- [ ] 業務規則放在聚合根，不是 Service
- [ ] 入站埠的 Javadoc 寫明「成功代表什麼」
- [ ] 出站埠的 Javadoc 寫明「實作必須保證什麼」
- [ ] 出站埠用業務語彙命名，不含技術詞彙
- [ ] 交易邊界正確（尤其：跨資源補償不在交易內）
- [ ] 框架例外沒有外洩到應用層
- [ ] Controller 沒有業務邏輯，`userId` 來自認證脈絡
- [ ] 新增的 MQ 消費端已處理冪等
- [ ] 領域／應用層測試不依賴 Spring
- [ ] `mvn test -pl flash-sale-api -Dtest=ArchitectureTest` 通過

---

## 常見錯誤

**在應用層 `import com.flashsale.infrastructure.*`。** 通常發生在「只是想用一下那個工具類」，
但這一行會讓整個模組的依賴方向反轉。工具類需要被兩層共用時，
正確做法是把它下推到領域層或抽成 Port。

**Use Case 變成貧血的轉發層。** 若 `XxxApplicationService` 的每個方法都只是
`repository.save(x)` 一行，那它沒有存在價值——業務規則跑到別的地方去了，去把它找回來。

**在熱路徑上加 `@Transactional`。** 即使方法內沒有 DB 操作，
這個註解仍會從連線池取得連線並開啟交易。秒殺尖峰下，連線池會在幾秒內耗盡。
