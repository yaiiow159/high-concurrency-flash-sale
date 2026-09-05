-- 商品圖片（ADR-0027）。
--
-- 存的是**物件鍵**而不是完整 URL：換 CDN 網域、換儲存端點都不該
-- 需要改資料。完整 URL 由應用層用設定裡的 base 組出來。

CREATE TABLE product_image
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    product_id  BIGINT       NOT NULL,
    -- 內容雜湊當鍵：{sha256}.{ext}。
    --
    -- 同一張圖上傳兩次只會有一個物件（商家換規格重傳、多商品共用情境圖
    -- 都很常見），而且 URL 不可變——CDN 與 ISR 都能永久快取。
    -- 用可變的鍵（product-123.jpg）的話，每次換圖都要清 CDN，
    -- 而那是一個會忘記做、且忘記了不會有錯誤訊息的步驟。
    object_key  VARCHAR(128) NOT NULL COMMENT '物件鍵 {sha256}.{ext}；不存完整 URL',
    content_type VARCHAR(64) NOT NULL,
    byte_size   BIGINT       NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0 COMMENT '0 為主圖',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 同一個商品不重複掛同一張圖。使用者連點兩次上傳按鈕是常態，
    -- 而內容雜湊相同代表就是同一張圖
    UNIQUE KEY uk_product_image (product_id, object_key),
    -- 「這個商品有哪些圖」是商品頁與列表的查詢，依排序取
    KEY idx_product_image_product (product_id, sort_order),
    -- 孤兒對帳要問「還有沒有人指向這個物件」，走這個索引
    KEY idx_product_image_object (object_key),
    CONSTRAINT ck_product_image_size CHECK (byte_size > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品圖片';


-- 上傳中的物件。
--
-- **這張表存在的唯一理由是讓孤兒對帳能分辨兩種物件**：
-- 「剛上傳、還沒掛到商品上」與「掛過但商品刪了」。
--
-- 位元組直傳物件儲存、不經過應用伺服器（ADR-0027 決策 2），
-- 代價是伺服器不知道上傳有沒有成功——只能靠前端回報，
-- 而回報可能丟失。少了這張表，一個上傳到一半就關掉分頁的物件
-- 會與真正的孤兒長得一模一樣，而對帳無從分辨。
CREATE TABLE media_upload
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    object_key  VARCHAR(128) NOT NULL,
    created_by  BIGINT       NOT NULL COMMENT '簽發給誰；追溯用',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_upload_key (object_key),
    KEY idx_media_upload_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='已簽發上傳授權的物件，供孤兒對帳判斷寬限期';
