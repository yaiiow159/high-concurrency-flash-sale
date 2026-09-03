-- 售後：退貨單與退款（ADR-0011）
--
-- 一張訂單可以有多張退貨單——先退一件、兩週後再退另一件是常見情境。
-- 因此這裡沒有 order_no 的唯一索引，也刻意不在 orders 表上加退貨欄位。

CREATE TABLE return_request
(
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    return_no             VARCHAR(64)  NOT NULL COMMENT '退貨單號（RMA- 前綴）',
    order_no              VARCHAR(64)  NOT NULL,
    user_id               BIGINT       NOT NULL,
    reason                VARCHAR(24)  NOT NULL COMMENT 'DEFECTIVE/NOT_AS_DESCRIBED/WRONG_ITEM/CHANGED_MIND/OTHER',
    reason_detail         VARCHAR(512) NULL,
    -- 是否需要買家寄回。由訂單狀態決定（已出貨才需要），不由呼叫端指定——
    -- 讓呼叫端自己宣告，「已出貨卻宣稱免寄回」就是一個免費拿貨的漏洞
    requires_goods_return TINYINT(1)   NOT NULL,
    status                VARCHAR(16)  NOT NULL COMMENT 'REQUESTED/APPROVED/RECEIVED/REFUNDED/REJECTED/CANCELLED',
    review_note           VARCHAR(512) NULL,
    created_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    reviewed_at           DATETIME(3)  NULL,
    received_at           DATETIME(3)  NULL COMMENT '收到退回品並完成驗收的時間',
    refunded_at           DATETIME(3)  NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_return_no (return_no),
    -- 查「這張訂單退過什麼」是計算可退數量的必經步驟，走在熱路徑以外但每次申請都會用到
    KEY idx_return_order (order_no),
    -- 買家的退貨列表：時間序，新到舊
    KEY idx_return_user_created (user_id, created_at),
    -- 客服後台的待審清單
    KEY idx_return_status (status, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='退貨單';


-- 退貨行。單價是從訂單行複製的快照，不是重新查來的價格——
-- 稽核一張退貨單時不必回頭拼訂單，就能驗證退了多少錢、憑什麼。
CREATE TABLE return_line
(
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    return_id    BIGINT         NOT NULL,
    sku_id       BIGINT         NOT NULL,
    sku_snapshot VARCHAR(255)   NOT NULL COMMENT '下單當時的商品名稱',
    unit_price   DECIMAL(12, 2) NOT NULL COMMENT '下單當時的單價',
    quantity     INT            NOT NULL,
    -- NULL 代表尚未驗收。FALSE 代表收到的貨不可再售，庫存不回補——
    -- 此時不補任何庫存流水，因為原本的 DEDUCT 已經記過那批貨離開了
    restockable  TINYINT(1)     NULL COMMENT '驗收結果：是否可再售',
    PRIMARY KEY (id),
    -- 同一張退貨單內一個 SKU 只出現一次。要退兩件就把 quantity 寫 2，
    -- 拆成兩行會讓「累計已退數量」多一種要處理的形狀
    UNIQUE KEY uk_return_line (return_id, sku_id),
    CONSTRAINT fk_return_line_request FOREIGN KEY (return_id) REFERENCES return_request (id),
    CONSTRAINT ck_return_line_quantity CHECK (quantity > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='退貨行';


-- 累計已退金額。
--
-- 這是防重複退款的第三層，也是唯一兩條退款路徑都會經過的地方——
-- 使用者退貨看得到退貨單，PaymentRefundScheduler 的競態補償看不到。
-- 不變式 refunded_amount <= amount 由聚合根維護，這裡用 CHECK 再壓一道：
-- 資料庫的約束擋得住繞過應用層的手動修補，而退款這件事上，
-- 「多退了一次」沒有任何事後對帳能補救。
ALTER TABLE payment
    ADD COLUMN refunded_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '累計已退金額'
        AFTER failure_reason,
    ADD CONSTRAINT ck_payment_refund_ceiling CHECK (refunded_amount <= amount);
