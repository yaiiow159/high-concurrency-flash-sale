-- 購物車
--
-- 沒有購物車表頭：購物車就是某個使用者名下的品項集合。
-- 多一張只有 id 與 user_id 的表，只會多出「使用者存在但購物車列不存在」
-- 這種要處理的中間態。
--
-- 刻意不存價格也不存商品名。那些每次顯示時從 Catalog 取——
-- 購物車回答的是「現在買要多少錢」，存快照會在商家調價後變成謊言：
-- 使用者看到舊價格，結帳時被收新價格。
-- 這與 order_line 刻意存快照剛好相反，兩者不可互換。

CREATE TABLE cart_item
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    sku_id     BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 同一個 SKU 在購物車裡只會有一行；重複加入是累加數量而非新增一行
    UNIQUE KEY uk_cart_user_sku (user_id, sku_id),
    -- 清理排程依 updated_at 掃描長期未動的購物車
    KEY idx_cart_updated (updated_at),
    CONSTRAINT ck_cart_quantity CHECK (quantity > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='購物車品項';
