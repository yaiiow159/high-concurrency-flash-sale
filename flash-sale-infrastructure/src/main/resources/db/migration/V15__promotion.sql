-- 優惠與券（ADR-0013）

CREATE TABLE promotion
(
    id            BIGINT         NOT NULL AUTO_INCREMENT,
    name          VARCHAR(128)   NOT NULL COMMENT '會被快照進訂單，改名不影響歷史訂單',
    type          VARCHAR(24)    NOT NULL COMMENT 'ITEM_DISCOUNT/ORDER_DISCOUNT/COUPON/SHIPPING，同時決定計算順序',
    rule          VARCHAR(24)    NOT NULL COMMENT 'FIXED_AMOUNT/PERCENTAGE',
    threshold     DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '門檻金額，0 為無門檻',
    value         DECIMAL(12, 4) NOT NULL COMMENT '固定折抵金額，或折扣率（0.2 = 折 20%）',
    -- 比例折扣沒有上限是一顆定時炸彈：一張全站八折用在十萬元的訂單上就是折兩萬。
    -- 領域層對 PERCENTAGE 強制要求這個值，這裡允許 NULL 是給 FIXED_AMOUNT 用的
    max_discount  DECIMAL(12, 2) NULL COMMENT '折抵上限；PERCENTAGE 必填',
    start_at      DATETIME(3)    NOT NULL,
    end_at        DATETIME(3)    NOT NULL,
    enabled       TINYINT(1)     NOT NULL DEFAULT 1,
    created_at    DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 查「現在有哪些可用的優惠」是下單路徑上的查詢，走這個索引
    KEY idx_promotion_active (enabled, start_at, end_at),
    CONSTRAINT ck_promotion_value CHECK (value > 0),
    CONSTRAINT ck_promotion_window CHECK (end_at > start_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='優惠規則';


CREATE TABLE coupon
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    promotion_id  BIGINT      NOT NULL COMMENT '折抵規則在 promotion 上；券只管「是誰的、能不能用」',
    code          VARCHAR(32) NOT NULL,
    status        VARCHAR(16) NOT NULL COMMENT 'ISSUED/USED/EXPIRED',
    expires_at    DATETIME(3) NOT NULL,
    used_order_no VARCHAR(64) NULL COMMENT '核銷在哪一張訂單；供對帳與客訴追溯',
    used_at       DATETIME(3) NULL,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_code (code),
    -- 「我有哪些可用的券」是結帳頁的查詢
    KEY idx_coupon_user_status (user_id, status, expires_at),
    CONSTRAINT fk_coupon_promotion FOREIGN KEY (promotion_id) REFERENCES promotion (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='發給使用者的優惠券';


-- 訂單上的折扣明細。
--
-- 存明細而不是只存一個 discount_amount：客服要回答的是「為什麼折了 320」，
-- 而那需要知道是哪幾個優惠、各折了多少。
--
-- source_type 與 name 都是**快照**。優惠會下架、券會過期、規則會改，
-- 而三個月後的客訴要看的是當時的內容——與 order_line 的商品名稱、單價同一個道理。
CREATE TABLE order_discount
(
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    order_id    BIGINT         NOT NULL,
    source_type VARCHAR(24)    NOT NULL COMMENT '折扣來源種類（快照，不綁列舉）',
    source_id   BIGINT         NULL COMMENT '對應的 promotion.id，供追溯',
    name        VARCHAR(128)   NOT NULL COMMENT '當時的優惠名稱（快照）',
    amount      DECIMAL(12, 2) NOT NULL COMMENT '折抵金額，正數',
    PRIMARY KEY (id),
    KEY idx_order_discount_order (order_id),
    CONSTRAINT fk_order_discount_order FOREIGN KEY (order_id) REFERENCES orders (id),
    -- 用負數表示折扣遲早會有人把符號弄反，資料庫這一層也擋住
    CONSTRAINT ck_order_discount_amount CHECK (amount > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='訂單折扣明細（快照）';


-- 種一筆示範優惠，讓功能一啟動就看得到效果。
-- 有效期給得很長：這是展示用資料，過期了反而讓人以為功能壞了。
INSERT INTO promotion (name, type, rule, threshold, value, max_discount, start_at, end_at, enabled)
VALUES ('滿 30000 折 2000', 'ORDER_DISCOUNT', 'FIXED_AMOUNT', 30000.00, 2000.0000, NULL,
        '2026-01-01 00:00:00', '2030-01-01 00:00:00', 1);


-- 訂單行的實付分攤金額。
--
-- 整單折扣是折在「訂單」上，退貨卻是退「一行」。少了這個欄位，
-- 退款只能用 unit_price × quantity 算，那退的是使用者沒付過的錢。
-- 全額退貨會被付款金額上限擋下，但**部分退貨不會**——那筆多退的錢
-- 仍然在上限之內，靜靜地流出去。
--
-- 既有訂單沒有折扣，實付就等於小計，用它回填。
ALTER TABLE order_line
    ADD COLUMN allocated_amount DECIMAL(12, 2) NULL COMMENT '整單折扣分攤後的實付金額' AFTER unit_price;

UPDATE order_line SET allocated_amount = unit_price * quantity WHERE allocated_amount IS NULL;

ALTER TABLE order_line
    MODIFY COLUMN allocated_amount DECIMAL(12, 2) NOT NULL COMMENT '整單折扣分攤後的實付金額';


-- 退貨行的退款金額。同樣不能再由單價推導。
ALTER TABLE return_line
    ADD COLUMN refund_amount DECIMAL(12, 2) NULL COMMENT '這一次實際退多少；不由單價推導' AFTER quantity;

UPDATE return_line SET refund_amount = unit_price * quantity WHERE refund_amount IS NULL;

ALTER TABLE return_line
    MODIFY COLUMN refund_amount DECIMAL(12, 2) NOT NULL COMMENT '這一次實際退多少；不由單價推導';
