-- 修正到貨通知訂閱的唯一性。
--
-- V26 的 uk_restock_pending (user_id, sku_id, notified_at) 想表達
-- 「未通知的只能有一筆」，但 **MySQL 的唯一索引允許多個 NULL**，
-- 所以在 pending 狀態下那個索引等於不存在。兩個後果：
--
-- 1. 訂閱只能靠 `insert ... where not exists` 擋重複，而那在 REPEATABLE READ
--    下會產生 gap lock——**實測連點四次會有兩次 deadlock**。
-- 2. 萬一真的插進兩筆 pending（例如有人把 isolation 調成 READ COMMITTED），
--    markNotified 的批次 UPDATE 會把同一個時間戳寫進兩列，撞上這個索引而
--    整輪回滾。那會讓到貨通知**永久停擺**，而外部只看得到每分鐘一行 ERROR。
--
-- 改用生成欄位模擬「部分唯一索引」：未通知時是 1，已通知時是 NULL。
-- 唯一索引允許多個 NULL 這件事，這次反過來為我們工作——
-- 未通知的每組 (user, sku) 只能有一個 1，已通知的歷史可以留任意多筆。
--
-- （不能用 id 當鍵：MySQL 不允許生成欄位引用 AUTO_INCREMENT 欄位，錯誤碼 3109。）
ALTER TABLE restock_subscription
    ADD COLUMN pending TINYINT(1) AS (IF(notified_at IS NULL, 1, NULL)) STORED
        COMMENT '未通知為 1、已通知為 NULL；用來模擬部分唯一索引';

ALTER TABLE restock_subscription
    DROP INDEX uk_restock_pending,
    ADD UNIQUE KEY uk_restock_pending (user_id, sku_id, pending);
