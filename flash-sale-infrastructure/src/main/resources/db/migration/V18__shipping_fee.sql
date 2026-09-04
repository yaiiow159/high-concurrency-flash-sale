-- 運費（ADR-0019）

-- SKU 的重量。
--
-- **既有商品給 1000 克的預設值，不是 0。**
-- 0 會讓 ShippingFeeCalculator 直接報錯（那是刻意的：重量 0 不可當成免運），
-- 而那會讓所有既有商品在遷移後立刻結不了帳。
-- 1000 克是一個保守的中間值，落在最低級距裡。
ALTER TABLE sku
    ADD COLUMN weight_grams INT NOT NULL DEFAULT 1000 COMMENT '單件重量（克），用於運費計費' AFTER price;


-- 訂單的運費。
--
-- **不進 total_amount。** ADR-0013 建立的恆等式
-- total_amount == Σ order_line.allocated_amount 保持不變，
-- 讓退款按行退的邏輯一行都不必改。
--
-- 訂單實際要付的是 total_amount + shipping_fee，由 Order.payableAmount() 導出。
ALTER TABLE orders
    ADD COLUMN shipping_fee DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '運費；不計入 total_amount' AFTER total_amount,
    ADD COLUMN shipping_method VARCHAR(24) NOT NULL DEFAULT 'HOME_DELIVERY' COMMENT 'HOME_DELIVERY/CVS_PICKUP' AFTER shipping_fee;


-- 運費費率表。
--
-- **放資料庫而不是程式碼**：運費是營運會調的東西（換物流商、油價、促銷檔期），
-- 而每次調整都要改程式碼並重新部署是不合理的。
--
-- 查表時取「重量上限 >= 訂單重量」裡最小的那一筆。
CREATE TABLE shipping_rate
(
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    method           VARCHAR(24)    NOT NULL COMMENT 'HOME_DELIVERY/CVS_PICKUP',
    zone             VARCHAR(24)    NOT NULL COMMENT 'MAIN_ISLAND/OUTLYING_ISLAND',
    max_weight_grams INT            NOT NULL COMMENT '這一級的重量上限（含）',
    fee              DECIMAL(12, 2) NOT NULL,
    created_at       DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 同一個 (方式, 區域) 下不可有重複的重量上限——
    -- 重複的話「取最小的那一筆」會變成不確定的結果，而那不會拋任何例外
    UNIQUE KEY uk_shipping_rate (method, zone, max_weight_grams),
    CONSTRAINT ck_shipping_rate_weight CHECK (max_weight_grams > 0),
    -- 0 是合法的（免運級距），負數不是——那不是運費，是送錢
    CONSTRAINT ck_shipping_rate_fee CHECK (fee >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='運費費率表';


-- 宅配費率。離島是本島的兩到三倍，而且級距比本島少一級——
-- 超過 20 公斤的離島件要另外談，此時計算會報錯而不是猜一個數字。
INSERT INTO shipping_rate (method, zone, max_weight_grams, fee)
VALUES ('HOME_DELIVERY', 'MAIN_ISLAND', 5000, 80.00),
       ('HOME_DELIVERY', 'MAIN_ISLAND', 20000, 120.00),
       ('HOME_DELIVERY', 'MAIN_ISLAND', 50000, 200.00),
       ('HOME_DELIVERY', 'OUTLYING_ISLAND', 5000, 200.00),
       ('HOME_DELIVERY', 'OUTLYING_ISLAND', 20000, 350.00);


-- 免運優惠：滿 2000 免運。
--
-- **它是一個 Promotion，不是一個獨立的「免運門檻」欄位**（ADR-0019 決策 6）。
-- 這讓「滿 2000 免運」與「滿 1000 折 100」用同一套規則、同一個後台、
-- 同一份快照進訂單。
--
-- 折抵值給一個夠大的數字並靠上限夾住——實際折抵不會超過運費本身，
-- 那是計價引擎的 discount.min(running) 保證的。
INSERT INTO promotion (name, type, rule, threshold, value, max_discount, point_cost,
                       start_at, end_at, enabled)
VALUES ('滿 2000 免運', 'SHIPPING', 'FIXED_AMOUNT', 2000.00, 9999.0000, NULL, NULL,
        '2026-01-01 00:00:00', '2030-01-01 00:00:00', 1);
