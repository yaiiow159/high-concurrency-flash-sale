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
  ├── catalog/             商品（SPU）聚合根、SKU、類目樹、規格值物件
  ├── order/               訂單聚合根、訂單行、狀態機、領域事件
  ├── payment/             付款聚合根、金額值物件
  ├── identity/            使用者、refresh token 輪替鏈
  ├── stock/               扣減結果與扣減憑證值物件
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

先註冊並登入：

```bash
curl -X POST http://localhost:8080/api/v1/auth/register -H "Content-Type: application/json" -d "{\"email\":\"alice@example.com\",\"password\":\"correct-horse\",\"displayName\":\"Alice\"}"
```

```bash
curl -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d "{\"email\":\"alice@example.com\",\"password\":\"correct-horse\"}"
```

用回傳的 `accessToken` 發起搶購：

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
| `POST` | `/api/v1/orders` | Bearer | 一般下單，回 201 + 完整訂單（同步，全有全無） |
| `GET` | `/api/v1/orders/{orderNo}` | Bearer | 查詢訂單 |
| `GET` | `/api/v1/addresses` | Bearer | 收貨地址列表，預設排最前 |
| `POST` | `/api/v1/addresses` | Bearer | 新增地址，第一筆自動成為預設 |
| `PUT` | `/api/v1/addresses/{id}` | Bearer | 修改地址，不影響已成立的訂單 |
| `DELETE` | `/api/v1/addresses/{id}` | Bearer | 刪除地址 |
| `POST` | `/api/v1/addresses/{id}/default` | Bearer | 設為預設地址 |
| `GET` | `/api/v1/cart` | Bearer | 查看購物車，價格為當下目錄價 |
| `POST` | `/api/v1/cart/items` | Bearer | 加入購物車，同一 SKU 累加 |
| `PUT` | `/api/v1/cart/items/{skuId}` | Bearer | 調整數量，設 0 等同移除 |
| `DELETE` | `/api/v1/cart/items/{skuId}` | Bearer | 移除品項 |
| `POST` | `/api/v1/cart/merge` | Bearer | 登入後合併本地購物車 |
| `POST` | `/api/v1/orders/checkout` | Bearer | 從購物車結帳，成功後清空購物車 |
| `GET` | `/api/v1/orders/{orderNo}/shipment` | Bearer | 查詢出貨進度 |
| `GET` | `/api/v1/admin/shipments` | `seckill:admin` | 待處理出貨清單 |
| `POST` | `/api/v1/admin/shipments/{orderNo}/dispatch` | `seckill:admin` | 出貨；配送失敗後可再次呼叫以重送 |
| `POST` | `/api/v1/admin/shipments/{orderNo}/delivered` | `seckill:admin` | 標記送達，訂單轉為完成 |
| `POST` | `/api/v1/admin/shipments/{orderNo}/failed` | `seckill:admin` | 標記配送失敗，不改訂單狀態 |
| `GET` | `/api/v1/catalog/skus?ids=` | 匿名 | 批次查 SKU，供未登入的本地購物車定價 |
| `GET` | `/api/v1/catalog/products` | 匿名 | 商品列表，可依類目篩選（分頁上限 100） |
| `GET` | `/api/v1/catalog/products/{id}` | 匿名 | 商品詳情，含各 SKU 的規格與價格 |
| `GET` | `/api/v1/catalog/categories` | 匿名 | 類目樹 |
| `GET` | `/api/v1/activities` | 匿名 | 已上架活動列表 |
| `GET` | `/api/v1/activities/{id}` | 匿名 | 活動詳情（庫存餘量取自 Redis 即時值） |
| `POST` | `/api/v1/activities/{id}/warm-up` | `seckill:admin` | 手動預熱庫存（維運用） |
| `GET` | `/api/v1/admin/inventory/reconciliation/activities/{id}` | `seckill:admin` | 秒殺庫存對帳，只讀不改 |
| `GET` | `/api/v1/admin/inventory/reconciliation/skus/{id}` | `seckill:admin` | 一般庫存對帳，只讀不改 |
| `GET` | `/api/v1/admin/inventory/reconciliation/skus` | `seckill:admin` | 全量對帳，只回不平的 SKU |
| `POST` | `/api/v1/admin/inventory/activities/{id}/release` | `seckill:admin` | 釋放活動庫存回可售池 |
| `POST` | `/api/v1/auth/register` | 匿名 | 註冊 |
| `POST` | `/api/v1/auth/login` | 匿名 | 登入，回傳 access + refresh token |
| `POST` | `/api/v1/auth/refresh` | 匿名 | 續期；舊 refresh token 立即失效 |
| `POST` | `/api/v1/auth/logout` | 匿名 | 撤銷 refresh token |
| `GET` | `/api/v1/auth/me` | Bearer | 目前登入的使用者 |
| `POST` | `/api/v1/orders/{orderNo}/payments` | Bearer | 發起付款，回傳金流付款頁網址 |
| `GET` | `/api/v1/orders/{orderNo}/payments` | Bearer | 查詢付款狀態 |
| `POST` | `/api/v1/payments/callback` | 簽章 | 金流回調（由閘道呼叫） |

### 認證

採 **OAuth2 Resource Server + JWT**，使用者身分取自標準的 `sub` claim
（詳見 [ADR-0005](docs/adr/0005-jwt-resource-server-over-custom-filter.md)）。

**令牌採雙軌設計**，因為無狀態與可撤銷是互斥的：

| | Access token | Refresh token |
|---|---|---|
| 形式 | JWT（自包含） | 不透明隨機字串 |
| 有效期 | 15 分鐘 | 7 天 |
| 驗證 | 純 CPU，零遠端呼叫 | 查資料庫 |
| 可撤銷 | ❌ | ✅ |

每次續期都會**輪替**——舊的 refresh token 立即失效。若已輪替過的 token
再度出現，代表該憑證曾外洩，系統會撤銷**整條輪替鏈**逼雙方重新登入。

選 JWT 而非 Session 只有一個理由：**驗證是純 CPU 運算，不需要遠端呼叫**。
Session 每個請求都要讀一次 Redis，等於在熱路徑上憑空增加一次往返，
與整個削峰設計直接衝突。

由此推論出一條鐵則：**絕不可為了取得使用者資料而在認證環節查資料庫**——
一旦開始回查，JWT 的唯一優勢就消失了。

商品頁開放匿名瀏覽（不該逼使用者先登入才能看商品），
寫入操作一律需要令牌，管理端點另需 `seckill:admin` scope。

---

## 履約：訂單只記里程碑，物流細節在出貨單

```
訂單    PENDING_PAYMENT → PAID → SHIPPED → COMPLETED
出貨單  READY → IN_TRANSIT → DELIVERED
                    ↓ ↑
                  FAILED（可重送，不是終態）
```

判準是：**訂單狀態只收錄「會改變買家能做什麼」的轉折**。

- `PAID → SHIPPED` 收錄：出貨前可自由取消，出貨後必須走退貨
- 「運送中 → 派送中」不收錄：買家能做的事沒變，Ordering 也沒有邏輯分支在上面

這條線一鬆掉，訂單狀態機會長成一份物流狀態的副本，而副本永遠慢一步。

**配送失敗不是終態**，這與訂單狀態機刻意鎖死終態是不同的取捨：
訂單的終態牽涉金流與庫存，回頭一次就可能多退一次錢；
配送失敗只是「東西還在路上」，重試不產生任何不可逆的副作用。
重送會累加 `dispatch_count` 但**不覆寫首次出貨時間**——那是出貨時效的分母。

出貨單**不存收貨地址**：地址快照已在訂單上，再存一份就會出現
「訂單寫台北、出貨單寫高雄」這種沒有人能仲裁的狀態。

**已付款的訂單一律不可取消。** 取消會觸發庫存補償卻不會退錢，
允許 `PAID → CANCELLED` 等於製造出「庫存退了、錢沒退」的路徑，
而逾時關單排程隨時可能踩到它。要退錢必須走退款流程（P3 下一項）。

---

## 購物車：唯一「必須用引用」的地方

購物車與訂單對同一個問題給出相反的答案，而且兩邊都對：

| | 購物車 | 訂單 |
|---|---|---|
| 價格 | **引用**：每次顯示都重新取 | **快照**：建立時凍結 |
| 問的問題 | 「現在買要多少錢」 | 「當初成交是多少錢」 |
| 商家調價後 | 必須跟著變 | 絕不可變 |

購物車若存價格快照，商家調價後使用者會看到舊價格、結帳時被收新價格。
訂單若用引用，歷史訂單會在調價當下集體變動。
**把同一套規則套到兩邊，就一定有一邊是錯的。**

因此 `cart_item` 只有 `(user_id, sku_id, quantity)`，沒有價格也沒有商品名。

**購物車不鎖庫存。** 加入購物車不預扣也不預留——否則任何人都能靠一個迴圈
塞滿購物車把全站庫存凍結。庫存只在結帳當下檢查與扣減，
代價是「加到購物車時有貨、結帳時沒了」，那是誠實的：貨本來就先到先得。

未登入時購物車在 `localStorage`，登入後合併。**同一個 SKU 取兩邊較大值而非相加**——
在手機加了 2 件、電腦也加了 2 件的人想要的幾乎一定是 2 件；
相加會讓他在結帳頁看到一個從沒按過的數字。

---

## 收貨地址：快照，不是引用

訂單模型裡最容易做錯的一個欄位。直覺做法是在訂單上存 `addressId`，
顯示時再去地址簿查——那個做法在使用者搬家的那一刻就壞了：
三個月前已送達的訂單會顯示成寄到新家。

```
Address      「這個使用者現在的收貨地址是什麼」  ← 會變
ShippingInfo 「這張訂單當初要寄到哪裡」          ← 永遠不變
```

因此下單時**當場快照**六個欄位進訂單，`OrderEntity` 以 `updatable = false` 鎖住。
實測：把地址從臺北改成高雄、甚至整筆刪掉，訂單顯示的仍是下單當下的臺北地址。

這與訂單行的商品名快照、單價快照是同一條原則（ADR-0007）——
**訂單記錄的是「當時發生了什麼」，不是「現在的資料長什麼樣」**。
要改地址只有一種正確作法：取消原訂單，重新下單。那會留下兩筆可追溯的紀錄。

快照保留結構化欄位（縣市／區／街道分開）而非只存一個字串，
是為了物流介接——事後從一整串地址切回來是猜測，不是解析。

`Address` 屬於 Identity 脈絡，`ShippingInfo` 屬於 Ordering。
**兩者刻意互不認得**，轉換由應用層負責；讓 Identity 去 import Ordering 的型別，
等於把兩個脈絡黏在一起。代價是地址格式化各有一份，
那點重複遠比脈絡耦合便宜。ArchUnit 只管分層，抓不到這種耦合，只能靠 review。

---

## 雙下單通道：202 與 201 的差別不是風格問題

| | 一般下單 | 秒殺 |
|---|---|---|
| 成功狀態碼 | `201 Created` | `202 Accepted` |
| 回應 | 完整訂單，已成立 | 受理憑證，還要輪詢 |
| 一致性 | 單一交易，失敗全回滾 | 最終一致，靠補償 |
| 庫存 | MySQL 條件式 UPDATE | Redis Lua |
| 品項數 | 多品項 | 單品項 |

`202` 的意思是「收到了，還沒做」——秒殺回它是誠實的，訂單真的還沒建立。
一般下單回 `201` 也是誠實的，交易已提交、訂單確實存在。
把兩者統一成同一個狀態碼，就是對其中一邊說謊。

**一般通道刻意做成同步。** 它沒有削峰需求，把它也推進 MQ，
換來的是「為什麼買一本書也要輪詢」，而且失去了交易帶來的免費正確性：
任一品項庫存不足，整筆訂單連同先前幾行已扣的量一起回滾——
**回滾就是補償，而且是資料庫做的**。這條通道因此完全不需要
Outbox 補償、對帳兜底或退庫冪等。

價格一律由目錄決定，請求體沒有價格欄位。呼叫端若能指定價格，那就不叫價格了。

---

## 庫存雙模型：兩套機制，以「劃撥」隔開

秒殺與一般商品的流量特徵完全相反，用同一套機制必然有一邊做不好：

| 通道 | 機制 | 為什麼 |
|------|------|--------|
| `NORMAL` | MySQL 行 + 條件式 UPDATE | 數萬個 SKU 各自獨立、衝突率極低，DB 完全夠用且天然有交易保證 |
| `SECKILL` | Redis Lua | 所有請求競爭同一行，DB 鎖會排隊塌陷 |

呼叫端只認得 `InventoryService` 一個埠，路由由 `RoutingInventoryService` 依通道決定。

**同一個 SKU 可能兩邊都在賣，而兩個真實來源必然超賣。** 解法是劃撥：

```
劃撥 N 件   available -= N,  allocated += N        總量不變
秒殺進行中  只動 Redis                              MySQL 不參與
一般銷售    只動 available                          Redis 不參與
活動結束    allocated -= N,  available += 未售量     總量減少 = 實際銷量
```

劃撥期間兩邊各自有唯一的真實來源。順序上**一律先動 MySQL 再寫 Redis**——
反過來的失敗模式是「Redis 有貨、MySQL 沒扣」，那是超賣；
正著來最壞是少賣，而少賣可以事後補救。

庫存流水（`inventory_movement`）記的是**兩個增減量**而非一個數量：
釋放時回到可售池的量與從劃撥扣掉的量是兩個不同的值。
只記一個數字的流水重建不出現在的庫存，而重建正是流水存在的唯一理由。

---

## 商品目錄：為什麼價格掛在 SKU 而不是商品

「iPhone 16 Pro」是 SPU，「iPhone 16 Pro 256G 黑鈦金」才是實際被買賣的東西。
兩者價格不同、庫存也各自獨立：

```
Product 1（SPU）  iPhone 16 Pro
 ├─ SKU 2001  256G / 黑鈦金    NT$ 29,900
 ├─ SKU 2011  512G / 黑鈦金    NT$ 35,900
 └─ SKU 2012  256G / 原色鈦金  NT$ 29,900
```

把價格放在 SPU 上等於假設「一個商品只有一個價格」。
那個假設在第一個有規格的商品出現時就破裂，
而破裂時要改的不是一個欄位，是整個資料模型加上所有引用它的地方。

因此秒殺活動指向的是 **SKU 而非商品**（`seckill_activity.sku_id`）——
沒有「iPhone 16 Pro 全系列特價」這種東西，特價的一定是某個具體規格。

商品回應**刻意不含庫存**。庫存每秒變動數千次，商品描述幾週才改一次；
混在同一個回應裡，整個商品頁就失去被 CDN 快取的資格。
前端分開請求，與秒殺頁同一個手法。

規格屬性以 `LinkedHashMap` 保序——「256G / 黑」和「黑 / 256G」讀起來是不同的東西。

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

## 付款

訂單生命週期以付款閉環：`PENDING_PAYMENT → PAID`。
目前使用模擬金流閘道（`SimulatedPaymentGateway`），
接真實金流時替換該類別即可，`PaymentGateway` 介面不變。

**模擬的是流程而非走捷徑**：閘道非同步回調、簽章驗證、回調重送冪等，
三者都是真的。若模擬時走同步捷徑，那些情境只會在接上真實金流後才第一次出現——
而那是最糟的發現時機。

### 「錢收了但訂單入不了帳」

最需要想清楚的一個競態：

```
t0  使用者跳轉金流頁面
t1  逾時關單排程執行 → 訂單 CANCELLED、庫存退回
t2  使用者完成付款 → 閘道回調「成功」
    此時錢已收，但訂單已是終態
```

| 處理方式 | 為什麼不行 |
|---|---|
| 把付款標記為失敗 | 錢真的收了，帳上寫「沒收到」會讓對帳與現實脫節 |
| 強制把訂單改回 PAID | 庫存已退回並可能被別人買走，這會製造超賣 |
| **如實記錄收款成功，再標記待退款** | ✅ 本方案 |

付款狀態走 `SUCCEEDED → REFUND_PENDING → REFUNDED`，
並且**同時發出收款成功與需要退款兩個事件**——
只發後者會讓下游財務系統看到一筆沒有對應收入的支出。

`payment_callback_total{result="refund-required"}` 應恆為 0，
一旦出現就代表這個競態發生了。

---

## 庫存對帳

最終一致的系統沒有資料庫交易兜底，**偏差不會自癒，只會累積**。
補償失敗、訊息遺失、人為誤操作，每一次都在帳上留下差額，
而且沒有任何東西會主動告訴你。

每 10 分鐘核對三條恆等式。**三條缺一不可**，因為它們問的是不同的問題：

```
① 秒殺   Redis 餘量 + Σ(PENDING_PAYMENT + PAID 訂單數量) = 活動總庫存
② 一般   available = Σ 流水的 availableDelta   （allocated 同理）
③ 劃撥   Redis 有庫存的活動，MySQL 必須有對應的劃撥額度撐著
```

① 問「賣掉的有沒有被記錄」，② 問「MySQL 這邊的帳對不對」，
③ 問「這批貨到底是不是我們的」。前兩條可以同時完全帳平，而第三條不成立——
那代表 Redis 握著一批 `available` 從沒為它付過帳的貨，
秒殺賣一次、一般通道再賣一次。**這條是實作時真的踩到才補上的**，
經過見 [ADR-0008](docs/adr/0008-dual-inventory-model.md)。

| 偏差方向 | 判定 | 後果 | 處置 |
|---|---|---|---|
| 實際 < 應有 | `STOCK_LEAKED` | 少賣，庫存被鎖住 | 可自動修復（僅孤兒扣減） |
| 實際 > 應有 | `OVERSELL_RISK` | **超賣，不可逆** | 一律人工介入 |
| 庫存無劃撥支撐 | `OVERSELL_RISK` | **超賣，不可逆** | 一律人工介入 |

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

## 前端

秒殺頁、商品瀏覽與一般購買流程皆已實作（Nuxt 3 + Vue 3 + TypeScript），
詳見 [`web/`](web/)。

| 路徑 | 內容 |
|------|------|
| `/` | 限時搶購活動列表 |
| `/seckill/[id]` | 秒殺頁：倒數、庫存輪詢、開賣抖動 |
| `/products` | 商品列表，可依類目篩選 |
| `/products/[id]` | 商品詳情：選規格、直接購買 |
| `/orders/[orderNo]` | 訂單詳情與付款（含收貨資訊快照） |
| `/addresses` | 收貨地址簿管理 |
| `/cart` | 購物車（未登入用 localStorage） |
| `/checkout` | 結帳：選地址、確認下單 |

```bash
cd web && npm install && npm run dev   # http://localhost:5173
cd web && npm run typecheck            # 型別檢查（後端改欄位時這裡會失敗）
```

這一頁本身就是**削峰漏斗的第 0 層**：靜態部分由 ISR + CDN 承接，
庫存數字走獨立的輕量請求，開賣瞬間加隨機抖動把請求打散。

令牌採 BFF 設計——`server/api/auth/*` 把 refresh token 攔進 httpOnly cookie，
瀏覽器只拿得到 access token 且只存在記憶體中。

> 開發埠為 5173 而非 Nuxt 預設的 3000——後者被 `docker-compose.yml` 的 Grafana 佔用。

### 視覺系統

四個決定值得記下來：

- **中文刻意不下載 webfont。** 一套 CJK 字檔動輒 1–2 MB，
  而這個站的賣點就是秒殺頁的首屏速度。中文交給系統字體，
  只把拉丁字母與數字（Archivo / IBM Plex Mono）從 CDN 取。
- **數字一律等寬**（`.figure`）。價格、庫存、倒數、單號是這個介面的主要內容，
  非等寬會讓倒數每秒把版面推來推去。
- **卡片用 1px 邊框而非陰影**（只有可點的卡片在 hover 時抬起）。
  這是要讓人看清楚數字的介面，密集列表上的陰影只會讓版面浮躁。
- **CTA 底色與強調色分開**（`--cta` / `--accent`）。主要按鈕需要行動感，
  文字連結需要可讀，兩者的最佳亮度本來就不同——共用一個值時按鈕會顯得沉。

**商品視覺**：目錄還沒有圖片欄位（那屬於 P4 營運後台），
但純文字的商品格在網格裡看起來像後台列表。折衷是從 ID 推導一個**確定性**的色塊，
色盤是手挑的六個色調而非 HSL 公式——照公式繞色環算出來的顏色十之八九是濁的，
而且相鄰商品會撞成同一種灰紫。

**手機版有底部固定操作列**（商品、購物車、結帳、秒殺頁）。
主要動作永遠在拇指構得到的地方，而不是跟著內容捲走——
那是手機轉換率最常見的漏水點。用到的頁面必須加 `.pb-action-bar`，
否則最後一段內容會被永久蓋住。

顏色全部走 CSS 變數，深淺兩色只覆寫同一組 token——
元件裡沒有任何 `dark:` 前綴，因為漏掉一個就會出現
「深色背景配深色文字」這種只在其中一個主題下才看得到的 bug。

共用元件（`AppButton`／`AppCard`／`StatusBadge`／`MoneyText`／`EmptyState`）
存在的理由不是少寫 class，而是先前每頁各自拼樣式，
同一個「主要動作」在不同頁面有三種內距與兩種圓角。

型別目前仍是手寫的（`app/types/api.ts`）。實測已證明這會漂移——
後端把 `ActivityView.productId` 改成 `skuId` 時，前端型別安靜地留在舊欄位上。
下一步應改由 OpenAPI 產生，讓契約變動在**編譯期**就失敗。

### 關於 ISR 的一個誠實說明

`routeRules` 的 ISR 設定**確實有進到建置產物**（可在 `.output` 裡看到
`"isr": 300` 與 `"isr": false`），但用 `node .output/server/index.mjs`
直接跑時**不會有任何快取**——Nitro 的 node-server 預設沒有掛快取驅動，
回應也不帶 `cache-control`。

這些規則要在有 CDN 介接的平台（Vercel、Netlify）或自行設定
Nitro 快取驅動後才會生效。本地實測到的是「全部不快取」，
不是「ISR 正常運作」。

即使如此，`'/orders/**': { isr: false }` 仍必須寫：
訂單是每個使用者專屬的資料，被 CDN 快取等於把某個人的訂單發給下一個訪客。
這一條不是效能取捨，是安全邊界——它要在部署到 CDN 之前就存在，
而不是等出事之後才補。

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
