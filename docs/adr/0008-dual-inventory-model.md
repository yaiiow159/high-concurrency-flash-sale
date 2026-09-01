# ADR-0008：庫存雙模型與統一路由

**狀態**：已採納
**日期**：2026-09-01
**相關**：[ADR-0002](0002-stock-in-redis-not-database.md)（庫存放 Redis）、[ADR-0003](0003-lua-atomicity-over-distributed-lock.md)（鎖的使用邊界）、[ADR-0006](0006-dual-order-channels.md)（雙下單通道）

## 脈絡

現在只有一種庫存機制：Redis + Lua 原子扣減，且需要事先預熱。

這個設計是為「單一熱點、極端衝突」量身打造的。但電商平台會有**數萬個一般 SKU**，
它們的特徵完全不同——衝突率極低，多數 SKU 一天可能只賣出個位數。

[ADR-0002](0002-stock-in-redis-not-database.md) 已經預見了這個分岔點：

> **重新評估的時機**：若商品從「單一熱點」變成「數萬個低頻 SKU」，
> 衝突不再集中，資料庫樂觀鎖會是更簡單且足夠的方案，此時應撤掉 Redis 這一層。

現在這個時機到了。但情況比當初設想的更複雜：**兩種商品會同時存在**，
所以不是「撤掉 Redis」，而是「兩種機制並存」。

## 選項

### A. 全部走 Redis

| | |
|---|---|
| 缺點 | 數萬 SKU 要全部預熱（記憶體與預熱時間都不可行）；Redis 的故障面從「秒殺不可用」擴大到「全站不可賣」；每個 SKU 都要納入對帳，成本隨 SKU 數線性成長 |

最致命的是第二點：現在 Redis 掛掉只影響秒殺活動，全面 Redis 化後會變成全站停售。

### B. 全部走 MySQL

秒殺會塌陷。[ADR-0002](0002-stock-in-redis-not-database.md) 已完整論證
悲觀鎖（單行排隊）與樂觀鎖（高衝突下的活鎖）的失敗模式。
等於把已經解決的問題重新製造出來。

### C. 雙模型 + 統一入口路由（本方案）

## 決策

### 兩種機制，依「通道」路由

| 通道 | 機制 | 理由 |
|------|------|------|
| `NORMAL` | MySQL 行 + 樂觀鎖 | 衝突率低，DB 完全夠用，且天然有交易保證 |
| `SECKILL` | Redis Lua（現有） | 所有請求競爭同一行，DB 鎖會塌陷 |

**路由依據是通道，不是 SKU。** 這一點很關鍵，理由見下節。

應用層只認得一個埠：

```java
public interface InventoryService {
    InventoryDeduction deduct(DeductCommand command);
    boolean restore(RestoreCommand command);
}
```

基礎設施層以 `RoutingInventoryService`（`@Primary`）依 `command.channel()`
委派給 `JdbcStandardInventory` 或 `RedisSeckillInventory`。
**呼叫端不需要知道背後是 Redis 還是 MySQL**——
與 `MultiLevelActivityRepository` 用 Decorator 藏住快取是同一個手法。

### 同一個 SKU 同時在秒殺與一般銷售怎麼辦？

這是雙模型最容易出事的地方：**兩個真實來源必然導致超賣。**

解法是**劃撥（allocation）**——秒殺活動的庫存是從一般庫存
**預先切出來的獨立額度**，不是同一批貨的兩個視角。

```
inventory
  sku_id (PK), available, allocated, version
```

| 時機 | MySQL | Redis |
|------|-------|-------|
| 活動上架，劃撥 N 件 | `available -= N`，`allocated += N` | 建立活動庫存鍵 = N |
| 秒殺進行中 | 不動 | Lua 扣減 |
| 一般銷售進行中 | 樂觀鎖扣 `available` | 不動 |
| 活動結束，Redis 剩 R | `allocated -= N`，`available += R` | 刪除鍵 |

驗算：劃撥前總量 = `A`；劃撥後 = `(A−N) + N`；
活動結束後 = `(A−N+R) + 0`，比原本少了 `N−R`，
正好等於實際賣出的數量。**恆等式成立。**

劃撥期間，兩邊各自有明確且唯一的真實來源，不會互相干擾。

### 劃撥用分散式鎖，這正是它該出現的地方

劃撥是一次跨 MySQL 與 Redis 的操作，**無法原子化**。

但它是**低頻的**（活動上架時執行一次），
完全符合 [ADR-0003](0003-lua-atomicity-over-distributed-lock.md) 對鎖的使用界定：

> 這三者的共同點：**低頻**。用鎖串行化的代價可以忽略。

跟庫存預熱一樣，鎖只是減少衝突頻率，**不是唯一的正確性依據**——
`allocated` 欄位與流水表才是。即使鎖因節點時鐘漂移而失效，
`UPDATE ... WHERE available >= N` 的條件仍會擋下超額劃撥。

### 庫存流水表是必要的，不是加分項

```
inventory_transaction
  id, sku_id, type, quantity, ref_type, ref_no, created_at
  type: DEDUCT / RESTORE / ALLOCATE / RELEASE / ADJUST
```

**`available` 這個數字本身說明不了任何事。** 庫存出問題時，
只有流水能回答「這 37 件是怎麼消失的」。

這與秒殺 Redis 的扣減憑證機制（`orderNo|userId|quantity`）解決的是同一個問題，
只是換到關聯式資料庫的表述。

### 刻意不引入 `reserved` 欄位

「已下單未付款」的佔用量看似該有個欄位記錄，但它**可以從訂單表推導**
（`PENDING_PAYMENT` 訂單的數量加總），而且對帳本來就要算這個值。

多一個反正規化欄位就多一種不一致的可能。下單直接扣 `available`、
取消時退回，與秒殺的語意一致，狀態也更少。

## 代價

- **多了劃撥與釋放兩個流程，各自需要補償。** 劃撥時 MySQL 成功而 Redis 失敗，會造成庫存憑空消失（`allocated` 掛著但 Redis 沒有對應額度）。因為是低頻操作，採「失敗即拋錯 + 對帳兜底」而非完整 Saga——為低頻流程建 Saga 是過度設計。
- **對帳範圍要擴大。** 現有的 `StockReconciliationService` 只核對秒殺庫存，需要擴充涵蓋：一般庫存（`available` vs 訂單）、以及劃撥恆等式（`available + allocated` 的總量守恆）。這是 P1 必須一起做的，不能延後——沒有對帳的雙模型比單模型更危險。
- **活動結束的釋放時機需要協調。** Redis 庫存鍵有 `stockKeyTtlBuffer`（預設 2 小時）的保留期，讓尚未跑完的補償有鍵可退。釋放必須等這段緩衝過後才能執行，否則會把還會被退回的量算漏。
- **兩套機制的故障模式不同。** Redis 故障時秒殺 fail-closed（拒絕放行），MySQL 故障時一般下單直接失敗。監控與告警要分別涵蓋，不能只盯一套。
- **重新評估的時機**：若秒殺不再是業務重點，撤掉 Redis 分支並讓所有商品走 MySQL 即可——`InventoryService` 這層抽象讓這件事只需要刪掉一個實作與一段路由，呼叫端完全不受影響。這正是加這層間接的目的。
