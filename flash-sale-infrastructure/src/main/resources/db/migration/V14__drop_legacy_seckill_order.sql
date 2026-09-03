-- 移除舊的單品項訂單表。
--
-- V5 把訂單模型改成「訂單 + 訂單行」時，資料已經搬進 orders/order_line，
-- 但當時刻意保留舊表：那個發布若要回退，資料還在。
-- V5 的註解寫著「移除留到下一個發布週期」——V6 到 V12 都過去了，就是現在。
--
-- 確認過沒有任何程式碼還讀寫它：唯一還出現這個名字的地方是 V1（建表）
-- 與 V5（搬遷）這兩份歷史遷移本身。

-- 先再跑一次冪等搬遷。
--
-- 這在正常情況下是零筆的空操作——V5 一定先於本檔執行，而它之後
-- 就沒有任何程式碼再寫入舊表。留著它是因為 DROP 不可逆：
-- 萬一某個環境當初是在滾動部署中途執行 V5、而舊版實例又插進了幾筆，
-- 這一段會把它們補進來，而不是連同表一起刪掉。
-- 多跑一次的成本是零，賭錯的成本是永久遺失訂單。
INSERT INTO orders
    (order_no, user_id, channel, request_id, total_amount, status, created_at, paid_at, close_reason, version)
SELECT
    o.order_no, o.user_id, 'SECKILL', o.request_id, o.amount, o.status,
    o.created_at, o.paid_at, o.close_reason, 0
FROM seckill_order o
WHERE NOT EXISTS (SELECT 1 FROM orders n WHERE n.order_no = o.order_no);

INSERT INTO order_line
    (order_id, line_no, sku_id, sku_snapshot, unit_price, quantity, source_activity_id)
SELECT
    n.id, 0, a.sku_id, a.product_name,
    o.amount / o.quantity, o.quantity, o.activity_id
FROM seckill_order o
JOIN orders n ON n.order_no = o.order_no
JOIN seckill_activity a ON a.id = o.activity_id
WHERE NOT EXISTS (SELECT 1 FROM order_line l WHERE l.order_id = n.id);

DROP TABLE seckill_order;
