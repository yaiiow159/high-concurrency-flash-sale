-- 商品問答。
--
-- 與評價的差別是「誰能發言」：評價只有買過的人能寫（那是它可信的原因），
-- 問答任何登入者都能問——還沒買的人才有問題要問。

CREATE TABLE product_question
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    product_id  BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    content     VARCHAR(500) NOT NULL,
    -- 回答與提問放在同一列，不另開一張表：一個問題最多一個官方回答，
    -- 拆兩張表換到的是「多個回答」的能力，而那不是這裡要的東西
    answer      VARCHAR(1000) NULL,
    answered_by BIGINT       NULL,
    answered_at DATETIME(3)  NULL,
    -- 未回答的問題預設不公開：商品頁掛著一排沒人理的問題，
    -- 傳達的訊息比沒有問答區更糟
    published   TINYINT(1)   NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 商品頁只列已公開的，新到舊
    KEY idx_question_product (product_id, published, created_at),
    -- 「我問過的問題」
    KEY idx_question_user (user_id, created_at),
    -- 後台待回覆清單
    KEY idx_question_pending (answered_at, created_at),
    CONSTRAINT ck_question_content CHECK (CHAR_LENGTH(content) >= 5)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品問答';
