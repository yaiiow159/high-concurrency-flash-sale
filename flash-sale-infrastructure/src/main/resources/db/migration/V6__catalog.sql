-- =====================================================================
-- Catalog 脈絡：類目 · 商品（SPU）· SKU
--
-- SPU / SKU 分離是必要的：「iPhone 16 Pro」是 SPU，
-- 「iPhone 16 Pro 256G 黑」才是實際買賣的東西。
-- 價格掛在 SKU 上——256G 與 512G 價格不同，把價格放在 SPU 等於
-- 假設一個商品只有一個價格，那個假設在有規格的商品上立刻破裂。
--
-- 庫存刻意不在 Catalog 裡（見 ADR-0008）：它變動極快，
-- 與商品的靜態描述混在一起會讓快取策略無法區分兩者。
-- =====================================================================

CREATE TABLE IF NOT EXISTS category (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    parent_id  BIGINT      NULL COMMENT 'NULL 表示根類目',
    name       VARCHAR(64) NOT NULL,
    level      INT         NOT NULL COMMENT '存下來而非遞迴計算——讀多寫極少',
    sort_order INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_category_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品類目';


CREATE TABLE IF NOT EXISTS product (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    category_id BIGINT       NOT NULL,
    name        VARCHAR(128) NOT NULL,
    brand       VARCHAR(64)  NULL,
    description VARCHAR(2000) NULL,
    status      VARCHAR(16)  NOT NULL COMMENT 'DRAFT / ON_SHELF / OFF_SHELF',
    created_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    -- 列表查詢：WHERE status = 'ON_SHELF' AND category_id = ?
    KEY idx_product_status_category (status, category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品（SPU）';


CREATE TABLE IF NOT EXISTS sku (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    product_id BIGINT       NOT NULL,
    spec_json  VARCHAR(512) NOT NULL COMMENT '規格屬性，如 {"容量":"256G","顏色":"黑"}',
    price      DECIMAL(12,2) NOT NULL COMMENT '價格在 SKU 而非 SPU',
    barcode    VARCHAR(64)  NULL,
    status     VARCHAR(16)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_sku_product (product_id),
    KEY idx_sku_barcode (barcode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU（最小庫存單位）';


-- ---------------------------------------------------------------------
-- 種子資料：把既有的三個示範活動接上真正的商品與 SKU
-- ---------------------------------------------------------------------
INSERT INTO category (id, parent_id, name, level, sort_order) VALUES
    (1, NULL, '3C 產品', 1, 1),
    (2, 1,    '手機',    2, 1),
    (3, 1,    '耳機',    2, 2)
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO product (id, category_id, name, brand, description, status, created_at) VALUES
    (1, 2, 'iPhone 16 Pro', 'Apple', '旗艦機種，A18 Pro 晶片', 'ON_SHELF', NOW(3)),
    (2, 3, 'AirPods Pro',   'Apple', '主動降噪無線耳機',        'ON_SHELF', NOW(3)),
    (3, 2, '測試用商品',     'Test',  '尚未開賣活動所用',        'ON_SHELF', NOW(3))
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- SKU id 刻意對齊舊的 product_id（2001/2002/2003），
-- 讓既有訂單行的 sku_id 不必再搬一次
INSERT INTO sku (id, product_id, spec_json, price, barcode, status) VALUES
    (2001, 1, '{"容量":"256G","顏色":"黑鈦金"}', 29900.00, 'IP16P-256-BK', 'ON_SHELF'),
    (2002, 2, '{"顏色":"白"}',                    5990.00, 'APP-WH',       'ON_SHELF'),
    (2003, 3, '{"規格":"標準"}',                    999.00, 'TEST-STD',     'ON_SHELF')
ON DUPLICATE KEY UPDATE price = VALUES(price);

-- 追加其他規格，讓 SPU/SKU 分離在畫面上看得出來
INSERT INTO sku (id, product_id, spec_json, price, barcode, status) VALUES
    (2011, 1, '{"容量":"512G","顏色":"黑鈦金"}', 35900.00, 'IP16P-512-BK', 'ON_SHELF'),
    (2012, 1, '{"容量":"256G","顏色":"原色鈦金"}', 29900.00, 'IP16P-256-NT', 'ON_SHELF')
ON DUPLICATE KEY UPDATE price = VALUES(price);


-- ---------------------------------------------------------------------
-- 活動改為指向 SKU。
-- 舊欄位 product_id 存的值恰好就是新的 sku id，因此改名即可。
-- ---------------------------------------------------------------------
ALTER TABLE seckill_activity CHANGE COLUMN product_id sku_id BIGINT NOT NULL
    COMMENT '指向 SKU 而非 SPU：庫存與價格都掛在 SKU 上';
