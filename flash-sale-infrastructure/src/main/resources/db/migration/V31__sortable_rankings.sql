-- 讓「熱銷」「評分」「價格升冪」三個排序走得到索引。
--
-- 三者的病因不同，但症狀一樣：都是 filesort 掃完五萬筆商品。


-- ── 熱銷／評分：排序鍵在 join 進來的表上 ─────────────────────────
--
-- 驅動表只能是 product，於是 idx_product_sales_rank 一次都用不到。
-- 讓每個商品都有一列排行，查詢就能改成由排行表驅動的 inner join
-- （見 ProductListingQuery 的 straight_join）。
--
-- sold_quantity = 0 不是一筆銷售紀錄，是「這個商品存在且還沒賣出過」。
-- 這是一條不變量：少了某一列，那個商品會從排序中安靜消失
-- （建立商品時一併寫入，見 JpaProductRepository.save）。
INSERT INTO product_sales (product_id, sold_quantity, order_count)
SELECT p.id, 0, 0 FROM product p
WHERE NOT EXISTS (SELECT 1 FROM product_sales s WHERE s.product_id = p.id);

-- 平均分是運算式，運算式建不了索引，用生成欄位固化下來。
--
-- 這不違反「存的是總和與筆數，不是平均值」（CLAUDE.md 7-3）：
-- 真實來源仍是 rating_sum 與 rating_count，平均由資料庫自己算，
-- 應用層永遠不會寫它，因此不可能漂移。
ALTER TABLE product_rating
    ADD COLUMN average_rating DECIMAL(3, 2) AS (
        IF(rating_count = 0, 0, rating_sum / rating_count)
    ) STORED COMMENT '由 sum/count 生成，僅供排序用；真實來源仍是那兩欄';

ALTER TABLE product_rating
    ADD KEY idx_product_rating_rank (average_rating DESC, product_id DESC);

INSERT INTO product_rating (product_id, rating_sum, rating_count)
SELECT p.id, 0, 0 FROM product p
WHERE NOT EXISTS (SELECT 1 FROM product_rating r WHERE r.product_id = p.id);


-- ── 價格升冪：排序方向與索引對不上 ───────────────────────────────
--
-- `order by lowest_price asc, id desc` 是混合方向，而 idx_product_price_rank
-- 是全升冪的，正掃反掃都滿足不了它。價格降冪沒這個問題——`desc, desc`
-- 就是那個索引的反向掃描。
--
-- MySQL 8 支援降冪索引，建一個方向對得上的即可。
ALTER TABLE product
    ADD KEY idx_product_price_asc (status, lowest_price ASC, id DESC);
