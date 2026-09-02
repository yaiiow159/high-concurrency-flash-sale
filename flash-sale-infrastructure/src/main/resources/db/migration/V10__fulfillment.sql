-- 履約：出貨單
--
-- 沒有收貨地址欄位。地址快照在 orders 表上，出貨單再存一份就會出現
-- 「訂單寫台北、出貨單寫高雄」這種沒有人能仲裁的狀態——
-- 而那兩份資料理論上永遠應該相同。需要地址時從訂單取。

CREATE TABLE shipment
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    shipment_no    VARCHAR(64) NOT NULL COMMENT '出貨單號（Snowflake）',
    order_no       VARCHAR(64) NOT NULL,
    user_id        BIGINT      NOT NULL,
    carrier        VARCHAR(16) NULL COMMENT 'TCAT/HCT/POST/CVS/SELF',
    tracking_number VARCHAR(64) NULL COMMENT '物流單號',
    status         VARCHAR(16) NOT NULL COMMENT 'READY/IN_TRANSIT/DELIVERED/FAILED/CANCELLED',
    failure_reason VARCHAR(256) NULL,
    dispatch_count INT         NOT NULL DEFAULT 0 COMMENT '派送次數，大於 1 代表曾失敗重送',
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    shipped_at     DATETIME(3) NULL COMMENT '第一次出貨時間；重送不覆寫，它是出貨時效的分母',
    delivered_at   DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipment_no (shipment_no),
    -- 一張訂單一張出貨單。未來支援分批出貨時把這個唯一索引降級成一般索引即可，
    -- 領域模型那邊也刻意沒有假設一對一
    UNIQUE KEY uk_shipment_order (order_no),
    -- 營運後台的待處理清單：依狀態篩選、依建立時間排序
    KEY idx_shipment_status (status, created_at),
    KEY idx_shipment_user (user_id),
    CONSTRAINT ck_shipment_dispatch CHECK (dispatch_count >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='出貨單';


-- 既有的已付款訂單補上出貨單。
--
-- 沒有這一段，V10 之前付款的訂單永遠不會出現在揀貨佇列裡——
-- 出貨單是由 order.paid 事件建立的，而那些事件早就投遞完了。
-- 遷移的責任是讓舊資料在新規則下也成立。
--
-- 單號用訂單號加前綴而非 Snowflake：這是一次性的補資料，
-- 保持可追溯（看得出是哪張訂單補的）比格式一致更有價值。
INSERT INTO shipment (shipment_no, order_no, user_id, status, created_at)
SELECT CONCAT('MIG-', o.order_no), o.order_no, o.user_id, 'READY', o.created_at
FROM orders o
WHERE o.status = 'PAID'
  AND NOT EXISTS (SELECT 1 FROM shipment s WHERE s.order_no = o.order_no);
