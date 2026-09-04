# 壓測

量三件事：**讀路徑**、**秒殺熱路徑**、**前端 SSR**。

一次跑完約 10 分鐘（不含建索引）。

---

## 為什麼原生跑，不進容器

壓測工具跑在**本機原生**，不放進 Docker。容器多一跳網路，
而這裡要量的正是延遲本身——多出來的零點幾毫秒會直接混進 p99，
讓「系統慢」與「量測方式慢」變得分不開。

## 為什麼用 autocannon 而不是 k6

同一個理由。k6 官方發行方式是容器，而 autocannon 是 npm 套件，
可以直接跑在受測機器上。

---

## 準備

```bash
docker compose up -d
mvn spring-boot:run -pl flash-sale-api

cd benchmark && npm install

# 種入 50,004 商品 / 100,007 SKU / 225 類目（約 7 秒）
docker compose exec -T mysql mysql --default-character-set=utf8mb4 -uroot -proot flash_sale < seed-catalog.sql

# 建搜尋索引（約 110 秒，5 萬筆）
curl -X POST localhost:8080/api/v1/admin/search/reindex -H "Authorization: Bearer $ADMIN_TOKEN"
```

## 跑

```bash
node read-bench.mjs                  # 讀路徑七個情境
node users.mjs                       # 建立 60 個壓測帳號並取 token
node seckill-bench.mjs <活動ID> 200 12   # 熱路徑：活動、併發、秒數
node web-bench.mjs                   # 前端 SSR（需先 npm run build 並啟動 :3100）
```

### 熱路徑要先解除單使用者限流

`flash-sale.rate-limit` 預設是「桶容量 5、每秒補 1」。不解除的話，
60 個帳號在 12 秒內總共只放行約 1,020 次請求，
**量到的會是限流器而不是系統**：

```bash
mvn spring-boot:run -pl flash-sale-api \
  -Dspring-boot.run.jvmArguments="-Dflash-sale.rate-limit.capacity=100000 -Dflash-sale.rate-limit.refill-per-second=100000"
```

量完記得**重啟回預設值**。

---

## 讀數時要注意的三件事

**1. `errors` 不是伺服器錯誤。** autocannon 的 `errors` 是用戶端 socket 錯誤
（50 併發下約 0.9%），與伺服器回的非 2xx 是兩件事。`bench.mjs` 分開報這兩欄，
相加會把壓測機自己的問題記到受測系統頭上。

**2. `Com_select` 有背景噪音。** 排程（Outbox relay、對帳、預熱）本身就會查詢，
實測空取樣也有 2 次。要量單一請求的查詢數，先量一次空取樣當基準線再相減。

**3. 全部跑在同一台機器上。** MySQL、Redis、Kafka、Elasticsearch、JVM
與壓測工具共用 CPU，絕對數值會被壓低。**有意義的是比例與趨勢**
（修正前後、併發上升時的曲線形狀），不是絕對的 QPS。

---

## 清理

```bash
docker compose exec -T mysql mysql --default-character-set=utf8mb4 -uroot -proot flash_sale < cleanup.sql
curl -X POST localhost:8080/api/v1/admin/search/reindex -H "Authorization: Bearer $ADMIN_TOKEN"
```

**索引一定要跟著重建。** 只清資料庫的話，搜尋還會回傳那 5 萬筆已經不存在的商品，
而那個症狀（搜得到、點進去 404）看起來會像搜尋壞了，不像沒清乾淨。
