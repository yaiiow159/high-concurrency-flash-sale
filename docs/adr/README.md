# 架構決策記錄（ADR）

這裡記錄的不是「我們做了什麼」，而是「**為什麼不做另一種選擇**」。

程式碼本身已經說明了做法，但說不出當初被否決的方案是什麼、否決的理由在什麼前提下成立。
半年後有人想「改成 X 應該更好」時，這些文件能讓他知道：X 當初被考慮過，
以及在什麼條件變化下，X 會重新變成正確答案。

| 編號 | 決策 | 狀態 |
|------|------|------|
| [0001](0001-modular-monolith-hexagonal.md) | 模組化單體 + 六角架構 | 已採納 |
| [0002](0002-stock-in-redis-not-database.md) | 庫存餘量放 Redis，不放聚合根與資料庫 | 已採納 |
| [0003](0003-lua-atomicity-over-distributed-lock.md) | 以 Lua 原子性取代分散式鎖 | 已採納 |
| [0004](0004-outbox-saga-over-seata.md) | Outbox + Saga 取代 Seata 分散式交易 | 已採納 |
| [0005](0005-jwt-resource-server-over-custom-filter.md) | 以 OAuth2 Resource Server 承載認證 | 已採納 |
| [0006](0006-dual-order-channels.md) | 雙下單通道，秒殺與一般下單分離 | 已採納 |
| [0007](0007-multi-line-order-aggregate.md) | 訂單聚合根重構為多品項 | 已採納 |
| [0008](0008-dual-inventory-model.md) | 庫存雙模型與統一路由 | 已採納 |
| [0011](0011-refund-saga.md) | 退款退貨作為獨立聚合根與第二個 Saga | 已採納 |
| [0012](0012-search-read-model.md) | 搜尋讀模型與最終一致窗口 | 已採納 |
| [0013](0013-promotion-pricing-engine.md) | 優惠計算引擎與折扣的快照邊界 | 已採納 |
| [0014](0014-review-and-rating-aggregate.md) | 評價的可信度與評分聚合 | 已採納 |
| [0015](0015-operations-console.md) | 營運後台的邊界 | 已採納 |
| [0016](0016-membership-points-and-tiers.md) | 會員積分與等級 | 已採納 |
| [0019](0019-shipping-fee-model.md) | 運費模型與訂單金額恆等式 | 已採納 |
| [0020](0020-order-create-partition-key.md) | 建單訊息的分區鍵改用訂單號 | 已採納 |
| [0021](0021-keyset-pagination.md) | 商店的商品列表改用 keyset 分頁 | 已採納 |
| [0022](0022-category-subtree-filter.md) | 依類目篩選要包含子樹 | 已採納 |
| [0023](0023-queue-depth-as-service-level.md) | 佇列深度作為服務水準與入場控制 | 已採納 |

## 待撰寫

[演進規劃](../roadmap/README.md) 已識別出以下需要決策記錄的主題。
P1 的三份（0006–0008）、P3 的 0011 與 P4 的 0012 已完成，其餘依階段撰寫。

| 編號 | 主題 | 何時 |
|------|------|------|
| ADR-0009 | 前端框架與渲染策略 | P0 |
| ADR-0010 | 界限脈絡切分與模組演進策略 | P1 前 |
| ADR-0017 | 真實金流的多形態付款生命週期 | P6 |
| ADR-0018 | 電子發票與號碼配號的併發控制 | P6 |
| ADR-0024 | 超商取貨與「逾期未取」終態 | 待寫 |
| ADR-0025 | 風控分數如何不增加熱路徑往返 | 待寫 |
| ADR-0026 | 帳號匿名化與不可變快照的衝突 | 待寫 |
| ADR-0027 | 商品圖片與第一個二進位資產 | 已寫，待實作 |

各項的缺口分析與判準見 [next-phase.md](../roadmap/next-phase.md)。

## 撰寫格式

每份 ADR 包含：**脈絡**（當時面對什麼問題）、**選項**（考慮過哪些做法）、
**決策**（選了什麼）、**代價**（放棄了什麼、什麼情況下要重新評估）。

最後一節是最重要的——沒有代價的決策通常代表沒想清楚。
