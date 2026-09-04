-- 移除 seed-catalog.sql 種下的壓測資料，回到原本的 4 件商品。
--
-- **先刪 SKU 與庫存再刪商品**：反過來會留下孤兒列，
-- 而那些孤兒會讓後續的庫存對帳報出偏差——一個為了清資料而製造的假警報。
DELETE i FROM inventory i JOIN sku s ON s.id = i.sku_id WHERE s.product_id > 4;
DELETE FROM sku WHERE product_id > 4;
DELETE FROM product WHERE id > 4;
DELETE FROM category WHERE id > 3;

DELETE FROM seckill_activity WHERE id >= 9000;
DELETE FROM app_user WHERE email LIKE 'loadtest%@perf.test' OR email = 'perfadmin@test.com';
