-- 付款單記下使用者選的付款方式
--
-- 先前前端送的 method 一直被忽略，付款單上看不出「當初怎麼付的」。
-- 既有資料一律補成 CREDIT_CARD：那是當時唯一走得通的路徑。
ALTER TABLE payment
    ADD COLUMN method VARCHAR(24) NOT NULL DEFAULT 'CREDIT_CARD' COMMENT '付款方式' AFTER status;
