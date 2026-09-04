-- 會員積分與等級（ADR-0016）

-- 會員帳戶：積分餘額與累計消費。
--
-- **餘額是快照，流水才是真實來源**（同庫存的 ADR-0008）。
-- 餘額回答「現在有多少」，流水回答「為什麼是這麼多」，
-- 而客服每天要回答的是後者。
CREATE TABLE member_account
(
    user_id          BIGINT         NOT NULL,
    -- **刻意沒有 CHECK (point_balance >= 0)**。
    --
    -- 退款絕對不能失敗。使用者用積分換了券、然後退貨，扣回會讓餘額變成負的——
    -- 而那正是事實：他欠這些點。加上 CHECK 的話退款交易會在資料庫層爆掉，
    -- 結果是「錢退不了，因為積分不夠扣」，一個沒有人能理解的錯誤。
    --
    -- 把餘額夾到 0 更糟：那讓「換券再退貨」的套利成功，而且是靜悄悄地成功。
    -- 負餘額的人要先補回正的才能再兌換——兌換的守衛條件自然涵蓋這件事。
    point_balance    BIGINT         NOT NULL DEFAULT 0,
    -- 等級由這個值決定，不是由積分餘額。用餘額算會讓使用者一花積分就降級，
    -- 而花積分正是我們希望他做的事
    cumulative_spend DECIMAL(14, 2) NOT NULL DEFAULT 0.00,
    -- 等級的**快取**。真實來源永遠是 cumulative_spend——
    -- 門檻調整後所有人的等級要立刻反映新規則，而不是等下一次消費
    tier             VARCHAR(16)    NOT NULL DEFAULT 'BRONZE',
    created_at       DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id),
    -- 後台要看「有多少白金會員」；沒有這個索引就得掃全表
    KEY idx_member_tier (tier)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='會員帳戶（積分餘額與累計消費）';


-- 積分流水。只增不刪。
CREATE TABLE point_transaction
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    -- 正數為入帳、負數為扣回
    delta         BIGINT      NOT NULL,
    -- **刻意的冗餘**：讓每一列自我完備。稽核一列時不必把前面所有列加一遍
    -- 就知道當時的餘額，而「加一遍」在流水有幾萬列時是不可行的
    balance_after BIGINT      NOT NULL,
    reason        VARCHAR(24) NOT NULL COMMENT 'ORDER_COMPLETED/RETURN_CLAWBACK/COUPON_EXCHANGE/ADJUSTMENT',
    ref_no        VARCHAR(64) NOT NULL COMMENT '來源單號；與 reason 一起構成冪等鍵',
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- **冪等的最後一道防線。**
    --
    -- 訂單完成事件是至少一次投遞，重放是常態不是異常。少了這個唯一索引，
    -- 一次重放就是一次免費的積分。兩個並行的重放會同時通過任何
    -- Java 端的「查過沒有」檢查——與訂單的 request_id 同一個角色。
    --
    -- 含 reason 是為了讓同一張訂單能有「完成入帳」與「退款扣回」兩筆而不衝突
    UNIQUE KEY uk_point_tx (user_id, reason, ref_no),
    -- 會員中心的流水查詢：依使用者、由新到舊
    KEY idx_point_tx_user (user_id, id DESC),
    CONSTRAINT ck_point_tx_delta CHECK (delta <> 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='積分流水（可重建餘額）';


-- 讓既有使用者都有一個帳戶。
--
-- 沒有這一步的話，舊使用者第一次進會員中心會查不到資料——
-- 而「查不到」與「有帳戶但都是 0」在畫面上長得一樣，卻要走兩條不同的程式路徑。
INSERT INTO member_account (user_id, point_balance, cumulative_spend, tier)
SELECT id, 0, 0.00, 'BRONZE' FROM app_user;


-- 可用積分兌換的優惠。
--
-- 掛在 promotion 上而不是另開一張「兌換商品表」：兌換出來的**就是一張券**，
-- 而券的規則已經在 promotion 裡了。另開一張表等於把同一件事描述兩次，
-- 而兩份描述遲早會不一致。
--
-- NULL 代表這個優惠不開放兌換——大多數優惠都是這樣（滿減是商家給的，不是換的）。
ALTER TABLE promotion
    ADD COLUMN point_cost BIGINT NULL COMMENT '兌換所需積分；NULL 表示不開放兌換' AFTER max_discount;

-- 種兩張可兌換的券，讓功能一啟動就看得到。
INSERT INTO promotion (name, type, rule, threshold, value, max_discount, point_cost,
                       start_at, end_at, enabled)
VALUES ('100 元折價券', 'COUPON', 'FIXED_AMOUNT', 1000.00, 100.0000, NULL, 100,
        '2026-01-01 00:00:00', '2030-01-01 00:00:00', 1),
       ('九折券（上限 500）', 'COUPON', 'PERCENTAGE', 0.00, 0.1000, 500.00, 300,
        '2026-01-01 00:00:00', '2030-01-01 00:00:00', 1);
