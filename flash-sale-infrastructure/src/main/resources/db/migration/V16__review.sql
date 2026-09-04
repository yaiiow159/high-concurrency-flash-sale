-- 商品評價與評分聚合（ADR-0014）

CREATE TABLE review
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    product_id  BIGINT       NOT NULL,
    sku_id      BIGINT       NOT NULL,
    -- 身分是「哪一筆訂單行」而不是「哪個使用者」：同一個人可以買同一件商品兩次，
    -- 那是兩次獨立的購買經驗，本來就該能各評一次
    order_no    VARCHAR(64)  NOT NULL,
    user_id     BIGINT       NOT NULL,
    -- 遮蔽過的顯示名稱**快照**。在畫面上遮等於完整姓名仍出現在 API 回應裡；
    -- 存引用則會讓使用者改暱稱後，三個月前的評價跟著變
    author_name VARCHAR(64)  NOT NULL,
    -- 用 INT 而不是 TINYINT：省下的 3 個位元組不值得讓 Java 端改用 short，
    -- 而 Hibernate 的 schema 驗證會把 TINYINT/int 的不一致當成錯誤擋在啟動時
    rating      INT          NOT NULL,
    content     VARCHAR(1000) NOT NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 「這筆訂單行只能評一次」的**最後一道**防線。
    -- 應用層的「還沒評價過」查詢擋不住兩個並行請求，
    -- 與訂單的 request_id 唯一索引同一個角色
    UNIQUE KEY uk_review_order_sku (order_no, sku_id),
    -- 商品頁的評價列表：依商品查、依時間新到舊
    KEY idx_review_product (product_id, created_at DESC),
    -- 「我寫過哪些評價」
    KEY idx_review_user (user_id, created_at DESC),
    CONSTRAINT ck_review_rating CHECK (rating BETWEEN 1 AND 5)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品評價';


-- 評分聚合。
--
-- **存總和與筆數，不存平均值。** 存平均是最直覺也最錯的選擇：
-- 新增一則評價要重算平均，而重算需要筆數——存導出值就重建不出原始事實。
-- 這與庫存流水記兩個增減量而不是單一 quantity 是同一件事。
--
-- 分佈同樣存下來：電商的評價區一定要有那條長條圖，它不該靠掃全表產生。
--
-- 更新一律是條件式增量 UPDATE（rating_sum = rating_sum + ?），
-- 不是「讀出來在 Java 裡加完再寫回」——後者在兩個人同時評價時會吃掉一則。
CREATE TABLE product_rating
(
    product_id   BIGINT      NOT NULL,
    rating_sum   BIGINT      NOT NULL DEFAULT 0 COMMENT '所有評分的總和；平均 = sum / count',
    rating_count INT         NOT NULL DEFAULT 0,
    count_1      INT         NOT NULL DEFAULT 0,
    count_2      INT         NOT NULL DEFAULT 0,
    count_3      INT         NOT NULL DEFAULT 0,
    count_4      INT         NOT NULL DEFAULT 0,
    count_5      INT         NOT NULL DEFAULT 0,
    updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (product_id),
    -- 這幾條約束擋的是「增量更新算錯方向」。聚合一旦變成負數，
    -- 平均分就會是一個沒有人看得懂的數字，而且回不去
    CONSTRAINT ck_product_rating_count CHECK (rating_count >= 0),
    CONSTRAINT ck_product_rating_sum CHECK (rating_sum >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品評分聚合（可增量維護）';
