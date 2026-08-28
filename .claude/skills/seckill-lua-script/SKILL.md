---
name: seckill-lua-script
description: 新增或修改 Redis Lua 腳本時的完整流程與契約同步規則。當要改動 seckill_deduct.lua、seckill_restore.lua、rate_limit.lua，或改動 StockDeductionOutcome / StockDeductionResult / RedisStockRepository / RedisKeys 時使用。涵蓋 hash tag 規則、回傳碼契約三方同步、TTL 處理與必跑的併發測試。
---

# 修改 Lua 腳本

Lua 腳本是這個系統唯一的強一致點。**改錯的後果是超賣，而且不會有任何錯誤訊息**——
測試全綠、日誌乾淨，只有對帳時才會發現庫存對不上。

因此這裡的流程比一般程式碼嚴格。

---

## 契約三方同步

`seckill_deduct.lua` 的回傳碼是一份三方契約，改任一處都必須同步另外兩處：

| 位置 | 角色 |
|------|------|
| `flash-sale-infrastructure/src/main/resources/lua/seckill_deduct.lua` | 產生回傳碼 |
| `flash-sale-domain/.../stock/StockDeductionOutcome.java` | 翻譯成領域語彙 + 映射業務例外 |
| `flash-sale-infrastructure/src/test/.../RedisStockRepositoryTest.java` | 驗證兩者一致 |

現行契約：

| 碼 | 列舉 | 語意 |
|----|------|------|
| `1` | `SUCCESS` | 扣減成功，第二個回傳值為本次綁定的訂單號 |
| `0` | `SOLD_OUT` | 庫存不足 |
| `-1` | `USER_LIMIT_EXCEEDED` | 累計購買超過限購 |
| `-2` | `STOCK_NOT_INITIALIZED` | 庫存未預熱 |
| `-3` | `DUPLICATE_REQUEST` | 重複請求，第二個回傳值為**首次**綁定的訂單號 |

`StockDeductionOutcome.fromCode()` 遇到未定義的碼會直接拋 `IllegalStateException`，
這是刻意的——寧可明確失敗，也不要讓一個未知的碼被當成成功處理。

---

## 修改流程

### 1. 先確認真的需要改 Lua

Lua 腳本執行期間 Redis 會**阻塞所有其他指令**。一個慢腳本能拖垮整個實例。

只有滿足以下條件才該寫進 Lua：

- 多個操作之間存在競態，且**必須原子**
- 操作總數在 10 條指令以內
- 沒有迴圈，或迴圈次數有明確上限

不滿足的話，考慮改用 pipeline（無需原子性時）或移到應用層。

### 2. 遵守 hash tag 規則

多鍵腳本要求所有鍵落在同一個 Redis Cluster slot。
新增鍵時，必須沿用 `RedisKeys` 的 hash tag 格式：

```java
private static final String ACTIVITY_SLOT = "seckill:{a%d}:";
```

大括號內的內容決定 slot。**忘記加 hash tag 的話，單機測試會完全正常，
上叢集立刻報 `CROSSSLOT Keys in request don't hash to the same slot`。**

### 3. 處理附屬鍵的 TTL

新建的鍵若沒有 TTL，活動結束後會永久殘留。腳本內的慣用寫法：

```lua
if redis.call('TTL', someKey) < 0 then
    redis.call('EXPIRE', someKey, ttlSeconds)
end
```

TTL 由呼叫端傳入（`RedisStockRepository` 從庫存鍵的 TTL 推導並本機快取），
**不要在腳本內寫死**。

### 4. 不要在腳本內用 `redis.call('TIME')`

Redis 7 以前，使用 `TIME` 會讓腳本被判定為非確定性而無法寫入副本。
需要時間就由呼叫端以 ARGV 傳入（參考 `rate_limit.lua`）。

### 5. 手動驗證

改完先用 redis-cli 直接跑，不必啟動整個應用：

```bash
docker exec -i flash-sale-redis redis-cli --eval flash-sale-infrastructure/src/main/resources/lua/seckill_deduct.lua "seckill:{a1}:stock" "seckill:{a1}:user" "seckill:{a1}:req" , 1 1 2 req-1 order-1 3600
```

注意 `,` 前後的空格——那是 redis-cli 分隔 KEYS 與 ARGV 的語法，少了空格會解析錯誤。

### 6. 補測試

**新增或修改回傳碼，必須在 `RedisStockRepositoryTest` 補對應的測試案例。**
若改動涉及併發語意，必須加入併發測試（參考 `OversellPrevention` 巢狀類別的寫法：
用 `CountDownLatch` 讓所有執行緒同時放行，製造真正的瞬間洪峰）。

```bash
mvn test -pl flash-sale-infrastructure -Dtest=RedisStockRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false
```

---

## 檢查清單

- [ ] 回傳碼三方同步（Lua / `StockDeductionOutcome` / 測試）
- [ ] `StockDeductionOutcome.toException()` 的 switch 已涵蓋新的碼（少一個分支編譯就會失敗，這是刻意用窮盡 switch 的原因）
- [ ] 所有鍵都帶正確的 hash tag
- [ ] 新建的鍵都有設定 TTL
- [ ] 腳本內沒有 `TIME`、沒有不定次數的迴圈
- [ ] 指令數在 10 條以內
- [ ] 已用 `redis-cli --eval` 手動驗證
- [ ] `RedisStockRepositoryTest` 全數通過（含 1000 執行緒防超賣測試）

---

## 常見錯誤

**把 `tonumber()` 忘掉。** `redis.call('GET', key)` 回傳的是**字串**，
`"100" < 5` 在 Lua 中的比較結果不是你想的那樣。所有數值比較前都要 `tonumber()`。

**在腳本裡對不存在的鍵做 `INCRBY`。** 這會憑空建立一個沒有 TTL 的鍵。
`seckill_restore.lua` 開頭的 `EXISTS` 檢查就是為了防這個——
庫存鍵已過期時退庫，會造出一個沒人看的「幽靈庫存」。

**以為回傳 `nil` 和回傳 `false` 一樣。** Lua 的 `nil` 轉成 Redis 回應會變成 `false`，
再轉到 Java 是 `null`。回傳結構化資料時一律用 table（`{ code, value }`），
並在 Java 端檢查長度。

**在 Lua 裡實作業務規則。** 腳本只該處理「必須原子」的部分。
活動是否上架、時間窗口是否有效這類判斷屬於領域層，寫進 Lua 就再也測不動了。
