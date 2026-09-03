-- 通知：站內信與 Email
--
-- 內容存的是**算好的文字**而不是「樣板代號 + 參數」。
-- Email 已經寄出去了，事後改樣板不會改變使用者信箱裡那封信，
-- 卻會改變我們的紀錄——客訴時我們就說不出「當時到底寄了什麼」。
-- 與 order_line 存 sku_snapshot 同一個理由。

CREATE TABLE notification
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    channel         VARCHAR(16)  NOT NULL COMMENT 'IN_APP/EMAIL',
    type            VARCHAR(24)  NOT NULL COMMENT 'ORDER_PAID/ORDER_SHIPPED/...',
    title           VARCHAR(128) NOT NULL COMMENT '建立當下算好的標題，快照',
    body            VARCHAR(1024) NOT NULL COMMENT '建立當下算好的內容，快照',
    reference_no    VARCHAR(64)  NULL COMMENT '關聯的訂單號或退貨單號',
    -- 來源事件 ID 是冪等鍵。Outbox 是至少一次語意，
    -- 少了它使用者會為同一次出貨收到三封一樣的信
    source_event_id VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL COMMENT 'PENDING/SENT/FAILED/UNDELIVERABLE',
    -- 實際寄達的地址，寄出當下才寫入且之後不可變。
    -- 存 user_id 引用的話，使用者換信箱後半年前的寄送紀錄會顯示成寄到新地址
    recipient       VARCHAR(255) NULL COMMENT '實際寄達的地址',
    failure_reason  VARCHAR(256) NULL,
    attempt_count   INT          NOT NULL DEFAULT 0,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sent_at         DATETIME(3)  NULL,
    read_at         DATETIME(3)  NULL COMMENT '僅站內信有意義；Email 是否被讀取我們無從得知',
    version         BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 冪等鍵含 channel：兩個管道可能一邊成功一邊失敗，
    -- 只用 source_event_id 的話 Email 重試會被站內信的存在擋掉，然後永遠寄不出去
    UNIQUE KEY uk_notification_source (source_event_id, channel),
    -- 站內信列表：某使用者的通知，新到舊
    KEY idx_notification_user (user_id, channel, created_at),
    -- 未讀數。read_at IS NULL 走不到索引，因此把它放進索引前綴之後由引擎過濾
    KEY idx_notification_unread (user_id, channel, read_at),
    -- 寄送排程撈取待發的 Email：依狀態與嘗試次數篩選
    KEY idx_notification_delivery (channel, status, attempt_count),
    CONSTRAINT ck_notification_attempt CHECK (attempt_count >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='通知';
