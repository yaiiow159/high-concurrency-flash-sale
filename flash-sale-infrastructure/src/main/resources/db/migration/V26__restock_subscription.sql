-- 到貨通知。
--
-- 秒殺 99.9% 的人搶不到，而他們現在什麼都做不了。訂閱把一個失敗的請求
-- 變成一次回訪的機會，而那是把流量留下來的唯一方法。

CREATE TABLE restock_subscription
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    sku_id      BIGINT      NOT NULL,
    -- 一次性訂閱：通知過就結束，不會因為之後再次缺貨又再次補貨而重發。
    -- 想再收就再訂一次——那是使用者明確表達了「我還要」
    notified_at DATETIME(3) NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 同一個人對同一個 SKU 只會有一筆「還沒通知」的訂閱。
    -- 用唯一索引擋而不是先查再插——連點兩次「有貨通知我」是常態。
    --
    -- **notified_at 放進唯一鍵，而且 MySQL 的唯一索引允許多個 NULL**：
    -- 那正好給出「未通知的只能有一筆、已通知的可以有多筆歷史」這個語意。
    UNIQUE KEY uk_restock_pending (user_id, sku_id, notified_at),
    -- 補貨時要撈「這個 SKU 有誰在等」
    KEY idx_restock_sku_pending (sku_id, notified_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='到貨通知訂閱';
