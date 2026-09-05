-- 領券中心：使用者自己領取優惠券。
--
-- 促銷引擎（ADR-0013）早就完整，但券只能由管理員發放——
-- 使用者沒有任何地方可以拿到券，於是整套機制在前台是看不見的。

-- 領取憑據。
--
-- **不能直接在 (user_id, promotion_id) 上建唯一鍵**：既有資料裡
-- 已經有人持有同一個促銷的兩張券（管理員補發、活動補償都會這樣），
-- 加了唯一鍵遷移當場就失敗。
--
-- 改成一個只有「自己領的」才填值的欄位：
-- MySQL 的唯一索引**允許多個 NULL**，所以管理員發放（NULL）不受限制，
-- 而自行領取的一人一張由唯一索引保證。
--
-- 這是「用唯一索引擋重複，而不是先查再寫」的第六次應用——
-- 先查再寫是 read-modify-write，兩個並行的領取請求都會通過檢查。
ALTER TABLE coupon
    ADD COLUMN claim_key VARCHAR(64) NULL
        COMMENT '自行領取的憑據 {userId}:{promotionId}；管理員發放為 NULL' AFTER code;

CREATE UNIQUE INDEX uk_coupon_claim ON coupon (claim_key);


-- 「哪些促銷可以領」是領券中心的查詢：進行中、未結束、且是 COUPON 型。
CREATE INDEX idx_promotion_claimable ON promotion (type, enabled, start_at, end_at);
