SET SESSION cte_max_recursion_depth = 1000000;
SET autocommit = 0;

-- 二級類目：12 個
INSERT INTO category (parent_id, name, level, sort_order)
SELECT 1, CONCAT('子類目 ', n), 2, n
FROM (WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n < 12) SELECT n FROM s) t;

-- 三級類目：每個二級掛 15 個 = 180 個葉節點
INSERT INTO category (parent_id, name, level, sort_order)
SELECT c.id, CONCAT(c.name, ' / 細項 ', s.n), 3, s.n
FROM category c
CROSS JOIN (WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n < 15) SELECT n FROM s) s
WHERE c.level = 2;

COMMIT;

-- 商品：50,000 筆，平均散佈在葉節點上
INSERT INTO product (category_id, name, brand, description, status, created_at)
SELECT
    leaf.id,
    CONCAT(ELT(1 + (s.n % 10), '極致','輕量','旗艦','經典','職人','城市','戶外','沉浸','無界','日常'),
           ELT(1 + (s.n % 8), '無線耳機','行動電源','機械鍵盤','登山背包','保溫瓶','桌上型檯燈','人體工學椅','咖啡手沖組'),
           ' ', ELT(1 + (s.n % 5), 'Pro','Air','Max','SE','Ultra'), ' #', s.n),
    ELT(1 + (s.n % 12), 'Aurora','Kestrel','Nimbus','Vertex','Lumen','Cobalt','Terra','Onyx','Sable','Quartz','Halcyon','Meridian'),
    CONCAT('這是一段用於效能測試的商品描述，編號 ', s.n,
           '。內容長度刻意接近真實商品文案，以免列表查詢因為欄位過短而顯得比實際更快。'),
    'ON_SHELF',
    NOW(3) - INTERVAL (s.n % 365) DAY
FROM (WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n < 50000) SELECT n FROM s) s
JOIN (SELECT id, ROW_NUMBER() OVER (ORDER BY id) - 1 AS rn, COUNT(*) OVER () AS total
      FROM category WHERE level = 3) leaf
  ON leaf.rn = s.n % leaf.total;

COMMIT;

-- SKU：每個新商品 2 個規格
INSERT INTO sku (product_id, spec_json, price, weight_grams, barcode, status)
SELECT p.id,
       JSON_OBJECT('規格', ELT(v.n, '標準', '進階')),
       ROUND(199 + (p.id % 400) * 7.5, 0) + (v.n - 1) * 300,
       500 + (p.id % 6) * 400,
       CONCAT('BC', LPAD(p.id, 8, '0'), v.n),
       'ON_SHELF'
FROM product p
CROSS JOIN (SELECT 1 n UNION ALL SELECT 2) v
WHERE p.id > 4;

COMMIT;

-- 一般庫存：沒有這個列表頁會顯示成缺貨
INSERT INTO inventory (sku_id, available, allocated, version)
SELECT s.id, 500, 0, 0
FROM sku s
LEFT JOIN inventory i ON i.sku_id = s.id
WHERE i.sku_id IS NULL;

COMMIT;
