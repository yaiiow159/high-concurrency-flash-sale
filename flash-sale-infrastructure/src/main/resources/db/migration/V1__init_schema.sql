-- =====================================================================
-- 秒殺系統初始結構
--
-- 索引設計原則：每一條索引都對應一個實際查詢，不預先建「以後可能用得到」的索引。
-- 秒殺的訂單表寫入極度密集，多一條索引就多一份寫入放大。
-- =====================================================================

CREATE TABLE IF NOT EXISTS seckill_activity (
    id              BIGINT       NOT NULL COMMENT '活動 ID，由營運後台指定',
    product_id      BIGINT       NOT NULL COMMENT '商品 ID',
    product_name    VARCHAR(128) NOT NULL COMMENT '商品名稱快照',
    seckill_price   DECIMAL(12,2) NOT NULL COMMENT '秒殺價',
    total_stock     INT          NOT NULL COMMENT '活動總庫存，僅供預熱與對帳，不參與扣減',
    per_user_limit  INT          NOT NULL DEFAULT 1 COMMENT '每人限購數量',
    start_at        DATETIME(3)  NOT NULL COMMENT '開始時間（UTC）',
    end_at          DATETIME(3)  NOT NULL COMMENT '結束時間（UTC，不含）',
    status          VARCHAR(16)  NOT NULL COMMENT 'DRAFT / ONLINE / OFFLINE',
    version         BIGINT       NOT NULL DEFAULT 0 COMMENT '樂觀鎖版本',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 啟動預熱與首頁列表的查詢條件
    KEY idx_status_end (status, end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒殺活動';


CREATE TABLE IF NOT EXISTS seckill_order (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    order_no     VARCHAR(64)  NOT NULL COMMENT 'Snowflake 訂單號，請求進來時即產生',
    activity_id  BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    request_id   VARCHAR(64)  NOT NULL COMMENT '端到端冪等鍵，由前端產生',
    quantity     INT          NOT NULL,
    amount       DECIMAL(12,2) NOT NULL COMMENT '由活動聚合計算，不接受前端傳入',
    status       VARCHAR(24)  NOT NULL COMMENT 'PENDING_PAYMENT / PAID / CANCELLED / FAILED',
    created_at   DATETIME(3)  NOT NULL,
    paid_at      DATETIME(3)  NULL,
    close_reason VARCHAR(128) NULL COMMENT '取消或失敗原因',
    version      BIGINT       NOT NULL DEFAULT 0 COMMENT '樂觀鎖版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    -- 防重複下單的最後一道防線。Redis 冪等與 MQ 冪等都可能失效，唯有這條約束無條件成立。
    UNIQUE KEY uk_request_id (request_id),
    -- 逾期關單排程：WHERE status = ? AND created_at < ?
    KEY idx_status_created (status, created_at),
    -- 使用者訂單列表
    KEY idx_user_activity (user_id, activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒殺訂單';


CREATE TABLE IF NOT EXISTS outbox_event (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    event_id     VARCHAR(64) NOT NULL COMMENT '事件唯一識別，同時是消費端的冪等鍵',
    event_type   VARCHAR(64) NOT NULL COMMENT 'order.created / order.cancelled / order.paid',
    aggregate_id VARCHAR(64) NOT NULL COMMENT '訂單號，作為 MQ 分區鍵保證同單有序',
    payload      TEXT        NOT NULL COMMENT '事件的 JSON 內容',
    status       VARCHAR(16) NOT NULL COMMENT 'PENDING / PUBLISHED / DEAD',
    retry_count  INT         NOT NULL DEFAULT 0,
    last_error   VARCHAR(512) NULL,
    created_at   DATETIME(3) NOT NULL,
    published_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id),
    -- 中繼器掃描待投遞事件，以及清理排程篩選已投遞紀錄
    KEY idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='領域事件發件匣，取代分散式交易';
