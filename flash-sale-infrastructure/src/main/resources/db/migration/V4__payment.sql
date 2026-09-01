-- =====================================================================
-- Payment 脈絡
-- =====================================================================

CREATE TABLE IF NOT EXISTS payment (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    payment_no             VARCHAR(64)  NOT NULL COMMENT 'PAY- 前綴，避免與訂單號在日誌中混淆',
    order_no               VARCHAR(64)  NOT NULL,
    user_id                BIGINT       NOT NULL,
    amount                 DECIMAL(12,2) NOT NULL COMMENT '建立時從訂單複製，之後不可變',
    status                 VARCHAR(24)  NOT NULL COMMENT 'PENDING / SUCCEEDED / FAILED / REFUND_PENDING / REFUNDED',
    gateway_transaction_id VARCHAR(64)  NULL COMMENT '閘道交易編號，對帳的唯一憑據',
    created_at             DATETIME(3)  NOT NULL,
    paid_at                DATETIME(3)  NULL,
    failure_reason         VARCHAR(256) NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_no (payment_no),
    -- 防重複收款的結構性保證：一張訂單至多一張付款單。
    -- 失敗後的重試沿用同一張單，讓「這張訂單收了幾次錢」是明確的事實，
    -- 而不需要靠掃描多筆紀錄推斷。
    UNIQUE KEY uk_payment_order_no (order_no),
    -- 待退款掃描：WHERE status = 'REFUND_PENDING'
    KEY idx_payment_status (status),
    KEY idx_payment_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='付款單';
