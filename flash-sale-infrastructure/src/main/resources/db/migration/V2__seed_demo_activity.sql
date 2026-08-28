-- 本地開發與壓測用的示範資料。
-- 時間刻意設成「昨天開始、明年結束」，clone 下來就能直接搶購，不必先改資料庫。

INSERT INTO seckill_activity
    (id, product_id, product_name, seckill_price, total_stock, per_user_limit, start_at, end_at, status)
VALUES
    (1001, 2001, 'iPhone 16 Pro 秒殺專場', 29900.00, 1000, 2,
     DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_ADD(NOW(3), INTERVAL 365 DAY), 'ONLINE'),
    (1002, 2002, 'AirPods Pro 限量搶購', 5990.00, 200, 1,
     DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_ADD(NOW(3), INTERVAL 365 DAY), 'ONLINE'),
    (1003, 2003, '尚未開賣的活動（測試用）', 999.00, 50, 1,
     DATE_ADD(NOW(3), INTERVAL 30 DAY), DATE_ADD(NOW(3), INTERVAL 60 DAY), 'ONLINE')
ON DUPLICATE KEY UPDATE product_name = VALUES(product_name);
