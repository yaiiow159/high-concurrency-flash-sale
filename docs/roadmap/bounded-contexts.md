# 界限脈絡與模組演進

← 回到 [規劃總覽](README.md)

---

## 一、脈絡地圖

12 個界限脈絡。**上游被依賴、下游依賴他人**——箭頭方向就是依賴方向。

```
                    ┌──────────────┐
                    │   Identity   │  使用者 · 認證 · 地址
                    └──────┬───────┘
                           │ 被所有脈絡依賴
        ┌──────────────────┼──────────────────┐
        │                  │                  │
  ┌─────▼─────┐     ┌──────▼──────┐    ┌──────▼──────┐
  │  Catalog  │◄────┤  Inventory  │    │    Cart     │
  │ 商品 · SKU │     │  庫存（雙）  │    │   購物車     │
  └─────┬─────┘     └──────┬──────┘    └──────┬──────┘
        │                  │                  │
        │      ┌───────────┴──────────┐       │
        │      │                      │       │
        │  ┌───▼────┐          ┌──────▼───────▼──┐
        └──►Seckill │          │    Ordering     │
           │ 秒殺活動│─────────►│  訂單 · 訂單行   │
           └────────┘          └────────┬────────┘
                                        │
              ┌──────────┬──────────────┼──────────────┐
              │          │              │              │
        ┌─────▼────┐ ┌───▼──────┐ ┌─────▼─────┐ ┌──────▼──────┐
        │ Payment  │ │Fulfillment│ │ Promotion │ │Notification │
        │   付款    │ │  履約物流  │ │ 促銷優惠   │ │    通知      │
        └──────────┘ └───────────┘ └───────────┘ └─────────────┘

        ┌──────────┐  ┌──────────┐
        │  Search  │  │  Review  │      讀模型，經由事件同步
        │  搜尋     │  │  評價     │
        └──────────┘  └──────────┘
```

### 逐一說明

| 脈絡 | 職責 | 階段 | 現況 |
|------|------|------|------|
| **Identity** | 使用者、認證、收貨地址 | P0 | 只有 JWT 驗證，無使用者實體 |
| **Catalog** | 商品、SKU、類目、屬性、圖片 | P1 | ❌ 無 |
| **Inventory** | 庫存扣減與回補（雙模型） | P1 | 只有秒殺的 Redis 部分 |
| **Ordering** | 訂單、訂單行、狀態機 | P1 | 單品項，需重構 |
| **Seckill** | 秒殺活動配置與搶購通道 | ✅ | 已完成 |
| **Cart** | 購物車 | P2 | ❌ 無 |
| **Payment** | 付款、退款 | P0 模擬 / P3 真實 | ❌ 無 |
| **Fulfillment** | 出貨、物流追蹤 | P3 | ❌ 無 |
| **Promotion** | 優惠券、滿減、價格計算 | P4 | ❌ 無 |
| **Notification** | 站內信、Email、推播 | P3 | ❌ 無 |
| **Search** | 商品搜尋（讀模型） | P4 | ❌ 無 |
| **Review** | 評價、評分 | P4 | ❌ 無 |

---

## 二、跨脈絡通訊規則

脈絡之間怎麼互動，決定了這個單體會不會變成大泥球。**三條規則**：

### 規則 1：同步呼叫只能走「published interface」

脈絡 A 要用脈絡 B 的功能，只能透過 B 明確發布的介面，
**不可以直接碰 B 的聚合根或 Repository**。

```java
// ✅ Ordering 透過 Catalog 發布的介面取商品快照
public interface CatalogQueryApi {
    Optional<SkuSnapshot> findSku(Long skuId);
}

// ❌ Ordering 直接注入 Catalog 的 Repository
private final SkuRepository skuRepository;   // 違規
```

發布介面放在各脈絡的 `api` 子套件，其餘一律 package-private 或
以 ArchUnit 禁止跨脈絡 import。

### 規則 2：跨脈絡的狀態變更走事件，不走同步呼叫

訂單付款成功後要：扣正式庫存、通知使用者、更新搜尋索引、累積會員點數。

```java
// ❌ 在 Ordering 裡同步呼叫四個脈絡
inventoryService.commit(...);
notificationService.send(...);
searchIndexer.update(...);
memberService.addPoints(...);
```

問題：Ordering 認得了所有下游，任何一個掛掉付款就失敗，
而且新增第五個下游又要改 Ordering。

```java
// ✅ 發一個事件，下游各自訂閱
eventOutbox.append(List.of(OrderPaidEvent.of(order)));
```

**這個機制已經有了**——Outbox + Kafka 現成可用，邊際成本接近零。

### 規則 3：跨脈絡引用只存 ID，資料用快照

```java
// Order 需要商品資訊
private final Long skuId;              // 引用：用來追溯
private final SkuSnapshot skuSnapshot; // 快照：下單當下的名稱與價格
```

**為什麼兩個都要**：只存 ID，商家調價後歷史訂單金額會跟著變（財務災難）；
只存快照，就無法追溯這是哪個商品。

---

## 三、模組演進

### 現在

```
flash-sale-domain/          按「層」分模組，層內只有秒殺一個脈絡
flash-sale-application/
flash-sale-infrastructure/
flash-sale-api/
```

### 第一步：層內改為按脈絡分套件

```
flash-sale-domain/src/main/java/com/flashsale/domain/
├── shared/              共享核心：ErrorCode、BusinessException、DomainEvent
├── identity/
├── catalog/
├── inventory/
├── ordering/
├── seckill/
└── ...
```

application 與 infrastructure 比照辦理。**Maven 模組數量不變**，
只是套件結構從「單一脈絡」變成「多脈絡並列」。

新增 ArchUnit 規則守邊界：

```java
@Test
@DisplayName("脈絡之間只能透過發布介面互動")
void contextsMustNotReachIntoEachOther() {
    slices().matching("com.flashsale.domain.(*)..")
            .should().notDependOnEachOther()
            .ignoreDependency(alwaysTrue(), resideInAPackage("..shared.."))
            .check(classes);
}
```

> 共享核心（`shared`）是唯一允許被所有脈絡依賴的例外。
> **要嚴格控制它的內容**——什麼都往裡塞，它就會變成新的大泥球。
> 判準：只放「與任何業務概念都無關」的東西（錯誤碼、事件介面、值物件基底）。

### 第二步：某脈絡證明需要獨立生命週期時才抽模組

**判斷訊號**（滿足其一才動）：

- 這個脈絡的變更頻率明顯高於／低於其他脈絡
- 需要獨立的擴縮策略（例如搜尋索引重建吃大量 CPU）
- 需要獨立的發布節奏
- 團隊分工上有明確的所有權邊界

**在那之前，套件邊界 + ArchUnit 已經足夠**，而且遷移成本低得多。

### 關於模組命名

現在所有模組都叫 `flash-sale-*`，但平台已經不只是秒殺了。
建議在 P1 動訂單重構時**一併改名**（例如 `commerce-*`），
因為那次本來就要動大量 import，順手改的邊際成本最低。

**不要為了改名單獨開一次重構**——收益純粹是命名精確度，
不值得一次全域擾動。

---

## 四、關鍵資料模型

只列會影響架構決策的部分，欄位細節留到實作時定。

### Catalog

```
product           商品（SPU）
  id, name, category_id, brand_id, status, description

sku               最小庫存單位
  id, product_id, spec_json, price, barcode, status

category          類目（樹狀）
  id, parent_id, name, level, sort
```

**SPU / SKU 分離是必要的**。「iPhone 16 Pro」是 SPU，
「iPhone 16 Pro 256G 黑」是 SKU。庫存與價格掛在 SKU 上，不是 SPU。
現有的 `seckill_activity.product_id` 之後要指向 **SKU**，不是 product。

### Inventory

```
inventory                 一般商品庫存
  sku_id (PK), available, reserved, version

inventory_transaction     庫存流水（審計用）
  id, sku_id, type, quantity, ref_type, ref_no, created_at
```

扣減用樂觀鎖：

```sql
UPDATE inventory SET available = available - ?, version = version + 1
WHERE sku_id = ? AND available >= ? AND version = ?
```

**流水表是關鍵**。庫存出問題時，`available` 這個數字本身說明不了任何事；
只有流水能回答「這 37 件是怎麼消失的」。秒殺的 Redis 憑證機制解決的是同一個問題。

### Ordering

```
orders                    訂單（聚合根）
  id, order_no, user_id, status, channel,
  total_amount, discount_amount, shipping_fee, payable_amount,
  address_snapshot_json, created_at, paid_at, version

order_line                訂單行
  id, order_id, sku_id, sku_snapshot_json,
  unit_price, quantity, subtotal

order_state_log           狀態變更軌跡
  id, order_id, from_status, to_status, reason, operator, created_at
```

**`channel` 欄位區分 `NORMAL` / `SECKILL`**——同一張表、同一個聚合根，
只是建立路徑不同。這是主張 1 的具體落地。

**`order_state_log` 別省**。訂單狀態爭議（「我明明付款了怎麼變取消」）
是電商客訴的主要來源，沒有軌跡就只能查日誌，而日誌會過期。

### 訂單狀態機（擴充後）

現在只有 4 態，擴充後：

```
    CREATED ──────► PENDING_PAYMENT ──────► PAID ──────► SHIPPED ──────► COMPLETED
                          │                  │              │                │
                          │                  │              │                │
                          ▼                  ▼              ▼                ▼
                      CANCELLED         REFUNDING ◄─── RETURNING ◄──── (售後期內)
                          ▲                  │
                          │                  ▼
                       FAILED            REFUNDED
```

沿用現有的 `ALLOWED_TRANSITIONS` 表設計——合法轉移集中宣告在一處，
新增狀態只需改一張表，而非散落各處的 if-else。

---

## 五、實作順序建議

脈絡之間有依賴，順序不能隨意：

```
1. Identity      被所有脈絡依賴，最先
2. Catalog       Inventory 與 Ordering 都需要它
3. Inventory     Ordering 需要它
4. Ordering      重構聚合根（最關鍵）
5. Cart          依賴 Catalog + Inventory
6. Payment       依賴 Ordering
7. Fulfillment   依賴 Ordering + Payment
8. 其餘          可並行
```

**Seckill 脈絡在第 3、4 步要同步調整**：
庫存改由 `InventoryService` 路由、訂單改用新的聚合根。
現有的 70 支測試是這次遷移最重要的安全網——**遷移期間不要刪任何一支**。
