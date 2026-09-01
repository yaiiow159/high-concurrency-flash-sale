-- =====================================================================
-- 訂單聚合根多品項重構（ADR-0007）
--
-- 刻意分成兩個 migration：V5 建表與搬資料，舊表保留。
-- 移除 seckill_order 留到下一個發布週期——在那之前隨時可以回退，
-- 因為舊表的資料仍然完整。
-- =====================================================================

CREATE TABLE IF NOT EXISTS orders (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    order_no     VARCHAR(64)  NOT NULL COMMENT 'Snowflake 訂單號，請求進來時即產生',
    user_id      BIGINT       NOT NULL,
    channel      VARCHAR(16)  NOT NULL COMMENT 'NORMAL / SECKILL；僅供追溯與報表，不用於控制流程',
    request_id   VARCHAR(64)  NOT NULL COMMENT '端到端冪等鍵',
    total_amount DECIMAL(12,2) NOT NULL COMMENT '訂單行加總，建立後不可變',
    status       VARCHAR(24)  NOT NULL COMMENT 'PENDING_PAYMENT / PAID / CANCELLED / FAILED',
    created_at   DATETIME(3)  NOT NULL,
    paid_at      DATETIME(3)  NULL,
    close_reason VARCHAR(128) NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    -- 防重複下單的最後一道防線。Redis 冪等與 MQ 冪等都可能失效，
    -- 唯有這條約束無條件成立。
    UNIQUE KEY uk_request_id (request_id),
    KEY idx_status_created (status, created_at),
    KEY idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='訂單';


CREATE TABLE IF NOT EXISTS order_line (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    order_id           BIGINT       NOT NULL,
    line_no            INT          NOT NULL DEFAULT 0 COMMENT '行序，保證每次查詢的排列一致',
    sku_id             BIGINT       NOT NULL,
    sku_snapshot       VARCHAR(256) NOT NULL COMMENT '下單當下的商品名稱快照',
    unit_price         DECIMAL(12,2) NOT NULL COMMENT '下單當下的單價快照',
    quantity           INT          NOT NULL,
    source_activity_id BIGINT       NULL COMMENT '來自哪個秒殺活動；一般下單為 NULL',
    PRIMARY KEY (id),
    KEY idx_line_order (order_id),
    -- 對帳：某活動被哪些訂單行佔用了多少量
    KEY idx_line_activity (source_activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='訂單行';


-- 訂單狀態變更軌跡。
-- 訂單狀態爭議（「我明明付款了怎麼變取消」）是電商客訴的主要來源，
-- 沒有軌跡就只能查日誌，而日誌會過期、會被輪替、也不保證撈得到。
CREATE TABLE IF NOT EXISTS order_state_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(64)  NOT NULL,
    from_status VARCHAR(24)  NULL COMMENT 'NULL 表示訂單建立',
    to_status   VARCHAR(24)  NOT NULL,
    reason      VARCHAR(256) NULL,
    operator    VARCHAR(64)  NULL COMMENT '系統排程或使用者',
    created_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_log_order (order_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='訂單狀態變更軌跡';


-- ---------------------------------------------------------------------
-- 搬遷既有資料。舊的 seckill_order 是單品項，每筆對應一張單行訂單。
--
-- sku_id 暫以 activity 的 product_id 填入；Catalog 脈絡建立後
-- 會指向真正的 SKU（見 roadmap 的 P1 實作順序）。
-- ---------------------------------------------------------------------
INSERT INTO orders
    (order_no, user_id, channel, request_id, total_amount, status, created_at, paid_at, close_reason, version)
SELECT
    o.order_no, o.user_id, 'SECKILL', o.request_id, o.amount, o.status,
    o.created_at, o.paid_at, o.close_reason, 0
FROM seckill_order o
WHERE NOT EXISTS (SELECT 1 FROM orders n WHERE n.order_no = o.order_no);

INSERT INTO order_line
    (order_id, line_no, sku_id, sku_snapshot, unit_price, quantity, source_activity_id)
SELECT
    n.id, 0, a.product_id, a.product_name,
    -- 單價由總額回推：舊模型只存總額，而每筆都是單一品項
    o.amount / o.quantity, o.quantity, o.activity_id
FROM seckill_order o
JOIN orders n ON n.order_no = o.order_no
JOIN seckill_activity a ON a.id = o.activity_id
WHERE NOT EXISTS (SELECT 1 FROM order_line l WHERE l.order_id = n.id);
