-- 後台訂單管理不帶任何條件時是「全部訂單、新到舊、第 N 頁」。
-- 既有的 idx_user_created / idx_status_created 都以別的欄位開頭，
-- 這條查詢只能 filesort 十四萬列。
ALTER TABLE orders
    ADD KEY idx_orders_created (created_at);
