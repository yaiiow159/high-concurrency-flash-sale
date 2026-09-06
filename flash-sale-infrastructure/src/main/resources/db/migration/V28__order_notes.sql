-- 訂單備註。
--
-- 兩個欄位而不是一個：**買家備註與內部註記絕不可共用一欄**。
-- 共用的話，客服寫的「此客戶已電話確認，疑似黃牛」會出現在買家的訂單頁上。
-- 那不是一個可以靠「記得別亂寫」避免的問題。

ALTER TABLE orders
    -- 買家在結帳時填，之後不可改：它是出貨依據，
    -- 而訂單建立後不可變的規則（ADR-0007）也適用於它
    ADD COLUMN buyer_note VARCHAR(200) NULL COMMENT '買家備註，買家看得到',
    -- 營運內部用，永遠不回傳給買家的端點
    ADD COLUMN staff_note VARCHAR(500) NULL COMMENT '內部註記，只有後台看得到';
