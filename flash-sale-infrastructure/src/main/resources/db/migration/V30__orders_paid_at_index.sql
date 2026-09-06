-- 報表全部按 paid_at 篩，而 orders 上只有 idx_status_created (status, created_at)。
--
-- created_at 不等於 paid_at，所以那個索引只用得到第一欄——實測 EXPLAIN 的
-- key_len=98 正好是 status 單獨一欄的長度，paid_at 是取出資料後才過濾的。
--
-- 現在看不出來，因為這個庫裡 PENDING_PAYMENT 佔了十萬筆、被 status 條件擋掉了。
-- 生產環境會反過來：跑久之後已付款訂單就是絕大多數，查「今天」的報表會讀取
-- 歷史上所有已付款訂單再丟掉 99.9%。
--
-- status 放前面是因為它是等值 IN、paid_at 是範圍，只有這個順序範圍段才走得到索引。
ALTER TABLE orders
    ADD KEY idx_orders_paid (status, paid_at);
