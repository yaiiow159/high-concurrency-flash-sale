-- =====================================================================
-- Identity 脈絡：使用者與 refresh token
-- =====================================================================

-- 表名用 app_user 而非 user：後者在 MySQL 與 PostgreSQL 都是保留字，
-- 得靠引號才能查詢，維運時每一句 SQL 都要記得加引號。
CREATE TABLE IF NOT EXISTS app_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(254) NOT NULL COMMENT '已正規化為小寫',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 雜湊，含 salt 與成本因子',
    display_name  VARCHAR(50)  NOT NULL,
    role          VARCHAR(16)  NOT NULL COMMENT 'CUSTOMER / ADMIN',
    status        VARCHAR(16)  NOT NULL COMMENT 'ACTIVE / SUSPENDED',
    created_at    DATETIME(3)  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 信箱在寫入前已轉小寫，這條約束才擋得住大小寫不同的重複註冊
    UNIQUE KEY uk_user_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='使用者';


CREATE TABLE IF NOT EXISTS refresh_token (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    token_hash       VARCHAR(64)    NOT NULL COMMENT 'SHA-256 十六進位；只存雜湊，外洩也換不到令牌',
    user_id          BIGINT      NOT NULL,
    family_id        VARCHAR(32)    NOT NULL COMMENT '同一次登入衍生的輪替鏈，重用偵測時整條撤銷',
    issued_at        DATETIME(3) NOT NULL,
    expires_at       DATETIME(3) NOT NULL,
    revoked_at       DATETIME(3) NULL,
    replaced_by_hash VARCHAR(64)    NULL COMMENT '非 NULL 即已輪替；再被使用就是重用',
    PRIMARY KEY (id),
    UNIQUE KEY uk_token_hash (token_hash),
    -- 重用偵測：一次撤銷整條鏈
    KEY idx_rt_family (family_id),
    -- 停權與「登出所有裝置」
    KEY idx_rt_user (user_id),
    -- 過期清理排程
    KEY idx_rt_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Refresh token，支援輪替與重用偵測';
