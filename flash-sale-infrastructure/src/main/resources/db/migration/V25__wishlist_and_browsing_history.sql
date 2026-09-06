-- 收藏與瀏覽紀錄。
--
-- 兩張表形狀幾乎一樣（使用者 × 商品 + 時間），但語意完全不同：
-- 收藏是使用者「說」他想要，瀏覽紀錄是系統「觀察」到他看過。
-- 前者要保留到他自己取消，後者可以無聲淘汰。

CREATE TABLE wishlist_item
(
    user_id    BIGINT      NOT NULL,
    product_id BIGINT      NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    -- 複合主鍵就是冪等鍵：重複收藏同一件商品不是錯誤，
    -- 使用者連點兩下愛心不該拿到 500
    PRIMARY KEY (user_id, product_id),
    -- 「我的收藏」依加入時間新到舊
    KEY idx_wishlist_user_created (user_id, created_at DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='收藏';


CREATE TABLE browsing_history
(
    user_id    BIGINT      NOT NULL,
    product_id BIGINT      NOT NULL,
    -- 只留最後一次。同一件商品看十次不該在紀錄上出現十列——
    -- 那會把「最近看過」洗成同一件商品
    viewed_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id, product_id),
    KEY idx_history_user_viewed (user_id, viewed_at DESC),
    -- 「看了這個的人也看了」要反查同一件商品的其他瀏覽者
    KEY idx_history_product (product_id, viewed_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='瀏覽紀錄';
