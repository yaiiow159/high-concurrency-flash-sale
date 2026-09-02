-- 收貨地址簿，以及訂單裡的收貨資訊快照
--
-- 兩邊的欄位刻意重複，那份重複正是快照的全部意義：
-- 地址簿會變，訂單不能跟著變。訂單若只存 address_id，
-- 使用者搬家之後，三個月前已送達的訂單會顯示成寄到新家——
-- 那是出貨紀錄與客訴處理的依據被靜靜竄改。

CREATE TABLE address
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    recipient_name VARCHAR(32)  NOT NULL COMMENT '收件人',
    phone          VARCHAR(24)  NOT NULL COMMENT '聯絡電話',
    postal_code    VARCHAR(8)   NOT NULL COMMENT '郵遞區號',
    region         VARCHAR(32)  NOT NULL COMMENT '縣市',
    district       VARCHAR(32)  NOT NULL COMMENT '鄉鎮市區',
    street_address VARCHAR(128) NOT NULL COMMENT '街道地址',
    is_default     TINYINT(1)   NOT NULL DEFAULT 0,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 刻意「不」建 UNIQUE(user_id, is_default)：那會連「同一個使用者有多筆
    -- 非預設地址」都一起擋掉。MySQL 沒有部分唯一索引，
    -- 因此「每人最多一筆預設」只能由應用層在交易內維持。
    KEY idx_address_user (user_id, is_default)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='收貨地址簿';


-- 訂單的收貨資訊快照。
--
-- 全部可為 NULL：秒殺訂單在建立當下沒有地址（搶購請求只帶活動與數量，
-- 中間沒有選地址的環節），V8 之前建立的訂單也沒有。
-- 這不是資料缺漏，是那條通道的形狀——削峰的前提就是把非必要步驟
-- 移出下單當下。
ALTER TABLE orders
    ADD COLUMN ship_recipient   VARCHAR(32) NULL COMMENT '收件人快照' AFTER total_amount,
    ADD COLUMN ship_phone       VARCHAR(24) NULL COMMENT '聯絡電話快照' AFTER ship_recipient,
    ADD COLUMN ship_postal_code VARCHAR(8) NULL COMMENT '郵遞區號快照' AFTER ship_phone,
    ADD COLUMN ship_region      VARCHAR(32) NULL COMMENT '縣市快照' AFTER ship_postal_code,
    ADD COLUMN ship_district    VARCHAR(32) NULL COMMENT '鄉鎮市區快照' AFTER ship_region,
    ADD COLUMN ship_street      VARCHAR(128) NULL COMMENT '街道地址快照' AFTER ship_district;
