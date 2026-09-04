-- 商品銷量聚合。
--
-- 與 product_rating 是同一型的東西：衍生聚合、增量 UPDATE、存總數不存比率。
-- 「熱賣排序」與「已售 N 件」都需要它，而先前資料庫裡沒有任何已售出的統計。

CREATE TABLE product_sales
(
    product_id    BIGINT      NOT NULL,
    -- 存「件數」與「訂單數」兩個，而不是只存件數。
    -- 只有件數的話，「10 個人各買 1 件」與「1 個人買 10 件」
    -- 在排行榜上一模一樣，而那兩件事的熱門程度差很多
    sold_quantity BIGINT      NOT NULL DEFAULT 0 COMMENT '累計售出件數',
    order_count   BIGINT      NOT NULL DEFAULT 0 COMMENT '累計訂單數（同一張訂單只算一次）',
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (product_id),
    -- 退貨會扣回，而扣回的量以當初那筆入帳為上限，因此不該變成負數。
    -- 變負數代表增量方向算錯了，而那種錯誤不會拋例外——它只會讓排行榜錯掉
    CONSTRAINT ck_product_sales_quantity CHECK (sold_quantity >= 0),
    CONSTRAINT ck_product_sales_orders CHECK (order_count >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品銷量聚合';

-- 依銷量排序要走這個索引。帶上 product_id 讓 keyset 分頁的
-- 複合游標 (sold_quantity, product_id) 能直接用索引定位（ADR-0021）
CREATE INDEX idx_product_sales_rank ON product_sales (sold_quantity DESC, product_id DESC);


-- 已計入的訂單。
--
-- **這張表是消費端冪等的關鍵，不是可有可無的稽核。**
--
-- 銷量走增量 UPDATE，而 `auto-offset-reset` 是 earliest——
-- 新的 consumer group 第一次上線會**重放整個 topic**（CLAUDE.md 鐵則 4）。
-- 沒有這張表的話，那次重放會把每一筆歷史訂單再加一次，
-- 而症狀是「銷量憑空翻倍」，沒有任何錯誤訊息。
--
-- direction 讓「計入」與「退貨扣回」各自只發生一次：
-- 同一張訂單可以有一筆 SALE 與一筆 RETURN，但各自不會重複。
CREATE TABLE product_sales_applied
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    -- 計入時是訂單號，扣回時是**退貨單號**。
    --
    -- 一張訂單可以退很多次（部分退貨），全部用 order_no 當鍵的話
    -- 第二次退貨會被當成重複而安靜略過——銷量從此永遠偏高。
    -- 這與 point_transaction 的 uk_point_tx (user_id, reason, ref_no) 同型
    ref_no     VARCHAR(32) NOT NULL COMMENT 'SALE 為訂單號，RETURN 為退貨單號',
    direction  VARCHAR(8)  NOT NULL COMMENT 'SALE / RETURN',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_sales_applied (ref_no, direction)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='銷量已計入的憑據，供消費端與退貨扣回各自冪等';


-- 回填既有已付款訂單的銷量。
--
-- **只算已付款以後的狀態**，與消費端的計入時機一致——
-- 兩邊用不同的判準的話，回填完的第一天就會對不起來。
INSERT INTO product_sales (product_id, sold_quantity, order_count)
SELECT p.id, SUM(ol.quantity), COUNT(DISTINCT o.id)
FROM orders o
         JOIN order_line ol ON ol.order_id = o.id
         JOIN sku s ON s.id = ol.sku_id
         JOIN product p ON p.id = s.product_id
WHERE o.paid_at IS NOT NULL
GROUP BY p.id;

-- 回填的訂單也要記進冪等表，否則消費端重放歷史時會再加一次
INSERT INTO product_sales_applied (ref_no, direction)
SELECT DISTINCT o.order_no, 'SALE'
FROM orders o
WHERE o.paid_at IS NOT NULL;


-- 商品的最低可購買價，反正規化到 product 上。
--
-- **為了排序而存，不是為了少一次查詢。**
-- 列表顯示的最低價本來就用一次批次查詢帶回來（findLowestPrices），
-- 那個做法很好、不需要改。但「依價格排序」不一樣——
-- 排序要在資料庫裡對 5 萬列做，而
-- `order by (select min(price) from sku where product_id = p.id)`
-- 是一個對每一列都要執行的相關子查詢，正好重建 ADR-0021 才剛消掉的懸崖。
--
-- NULL 代表「沒有任何可購買的規格」。補 0 會讓它排到價格升冪的最前面，
-- 而那是錯的——一件買不到的商品不該是「最便宜的」。
ALTER TABLE product
    ADD COLUMN lowest_price DECIMAL(12, 2) NULL COMMENT '最低可購買 SKU 的價格；NULL 代表無可購買規格' AFTER brand;

UPDATE product p
SET p.lowest_price = (SELECT MIN(s.price) FROM sku s
                      WHERE s.product_id = p.id AND s.status = 'ON_SHELF');

-- 排序索引。帶上 id 讓 keyset 的複合游標 (排序值, id) 能直接定位，
-- 而不是掃出來再排（ADR-0021）
CREATE INDEX idx_product_price_rank ON product (status, lowest_price, id);
CREATE INDEX idx_product_newest ON product (status, id);
