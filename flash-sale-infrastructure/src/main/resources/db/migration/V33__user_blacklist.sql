-- 秒殺黑名單。與停權不同：被列入的人仍可登入、逛、下一般訂單，只是拿不到搶購資格。
-- 一個使用者一筆（主鍵），重列即覆寫；expires_at 為 NULL 代表永久。
CREATE TABLE user_blacklist
(
    user_id    BIGINT       NOT NULL,
    reason     VARCHAR(200) NOT NULL,
    created_by BIGINT       NULL COMMENT '操作的管理員',
    created_at DATETIME(3)  NOT NULL,
    expires_at DATETIME(3)  NULL,
    PRIMARY KEY (user_id),
    KEY idx_blacklist_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='秒殺黑名單';
