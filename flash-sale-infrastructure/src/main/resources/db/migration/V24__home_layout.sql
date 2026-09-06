-- 首頁版型管理。
--
-- 首頁原本是寫死的四個區塊，改版要動程式碼、要重新部署。
-- 這幾張表讓「首頁長什麼樣」變成資料。

CREATE TABLE home_section
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    type        VARCHAR(24)  NOT NULL COMMENT 'CAROUSEL / PRODUCT_RAIL / CATEGORY_GRID / FLASH_SALE',
    title       VARCHAR(64)  NOT NULL,
    subtitle    VARCHAR(128) NULL,
    -- 規則型（BEST_SELLING…）與人工選品（CURATED）都支援：
    -- 「熱門」用規則才不會過期，「當季限定」用規則選不出來
    source      VARCHAR(24)  NULL COMMENT 'BEST_SELLING / NEWEST / TOP_RATED / CATEGORY / CURATED',
    category_id BIGINT       NULL COMMENT 'source=CATEGORY 時使用',
    item_limit  INT          NOT NULL DEFAULT 8,
    sort_order  INT          NOT NULL DEFAULT 0,
    enabled     TINYINT(1)   NOT NULL DEFAULT 1,
    -- 「當季限定」靠這兩欄自動上下架，不需要排程也不需要事件
    visible_from DATETIME(3) NULL,
    visible_to   DATETIME(3) NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 首頁一次把全部版位撈出來，依排序輸出
    KEY idx_home_section_order (enabled, sort_order)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='首頁版位';


-- 人工選品。順序存在這裡而不是靠 product_id 排序——
-- 選品的重點就是「我要它照這個順序出現」。
CREATE TABLE home_section_product
(
    section_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    sort_order INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (section_id, product_id),
    KEY idx_home_section_product_order (section_id, sort_order),
    CONSTRAINT fk_home_section_product FOREIGN KEY (section_id)
        REFERENCES home_section (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='首頁版位的人工選品';


CREATE TABLE carousel_slide
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    -- 物件鍵而非完整 URL，與 product_image 同一個做法（ADR-0027）
    object_key  VARCHAR(128) NOT NULL,
    title       VARCHAR(64)  NULL,
    -- 只收站內相對路徑。放行外部網址等於讓能編輯輪播圖的人
    -- 在首頁掛任意連結，而那是釣魚頁最想要的位置
    link_url    VARCHAR(256) NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    enabled     TINYINT(1)   NOT NULL DEFAULT 1,
    visible_from DATETIME(3) NULL,
    visible_to   DATETIME(3) NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_carousel_order (enabled, sort_order)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='首頁輪播圖';


-- 種入預設版型，讓 clone 下來的首頁與改版前長得一樣。
-- 不種的話首頁會是空白，而那看起來像壞掉。
INSERT INTO home_section (type, title, subtitle, source, item_limit, sort_order)
VALUES ('CAROUSEL', '主視覺', NULL, NULL, 5, 0),
       ('FLASH_SALE', '限時搶購', '手速決定一切', NULL, 4, 1),
       ('CATEGORY_GRID', '逛逛分類', NULL, NULL, 12, 2),
       ('PRODUCT_RAIL', '熱門商品', '大家都在買', 'BEST_SELLING', 8, 3),
       ('PRODUCT_RAIL', '最新上架', NULL, 'NEWEST', 8, 4),
       ('PRODUCT_RAIL', '好評推薦', '評分 4.5 以上', 'TOP_RATED', 8, 5);
