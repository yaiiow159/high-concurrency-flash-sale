-- 庫存雙模型（ADR-0008）
--
-- 一般商品走這兩張表 + 樂觀鎖；秒殺商品的即時餘量仍在 Redis。
-- 兩者以「劃撥」隔開，各自有唯一的真實來源，不會對同一批貨各賣一次。

CREATE TABLE inventory
(
    sku_id     BIGINT      NOT NULL COMMENT 'SKU ID，直接當主鍵——庫存與 SKU 一對一',
    available  INT         NOT NULL DEFAULT 0 COMMENT '可自由販售的量',
    allocated  INT         NOT NULL DEFAULT 0 COMMENT '已劃撥給秒殺活動、由 Redis 代管的量',
    version    BIGINT      NOT NULL DEFAULT 0 COMMENT '樂觀鎖版本',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (sku_id),
    -- 兩個數字都不可為負。應用層已經擋過一次，這裡是最後一道——
    -- 資料庫的約束不會因為某個新寫的呼叫路徑忘了檢查而失效
    CONSTRAINT ck_inventory_non_negative CHECK (available >= 0 AND allocated >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='SKU 庫存';

CREATE TABLE inventory_movement
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    sku_id          BIGINT      NOT NULL,
    type            VARCHAR(16) NOT NULL COMMENT 'DEDUCT/RESTORE/ALLOCATE/RELEASE/ADJUST',
    available_delta INT         NOT NULL COMMENT '對可售量的增減，帶正負號',
    allocated_delta INT         NOT NULL COMMENT '對劃撥量的增減，帶正負號',
    ref_type        VARCHAR(16) NOT NULL COMMENT 'ORDER/ACTIVITY/MANUAL',
    ref_no          VARCHAR(64) NOT NULL COMMENT '來源單號',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 冪等的最後一道保險。應用層會先查再寫，但兩者之間有時間差；
    -- 兩台機器同時釋放同一場活動時，只有資料庫的裁決不受競態影響。
    --
    -- type 與 sku_id 都不可從唯一鍵中省略：
    --   少了 type   → 同一筆訂單的 DEDUCT 與後續 RESTORE 會互相排斥
    --   少了 sku_id → 多品項訂單的第二行會被擋下（同一個 order_no、同樣是 DEDUCT，
    --                 只有 SKU 不同）。訂單自 ADR-0007 起就是多品項的
    UNIQUE KEY uk_movement_ref (ref_type, ref_no, type, sku_id),
    KEY idx_movement_sku (sku_id, created_at),
    KEY idx_movement_created (created_at),
    -- 兩邊都沒動的流水記了也沒用，只會讓稽核紀錄多出雜訊
    CONSTRAINT ck_movement_not_empty CHECK (available_delta <> 0 OR allocated_delta <> 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='庫存異動流水';


-- ---------------------------------------------------------------------------
-- 期初資料
--
-- 遷移的責任是讓新舊資料在同一套規則下都成立，不只是把表建出來。
-- 既有的三場秒殺活動在 Redis 裡已經有庫存，若這裡不補上對應的劃撥紀錄，
-- 那些量就成了「憑空存在」的額度：對帳會看到 Redis 有貨、MySQL 卻沒有
-- 對應的 allocated，於是每一輪都報不平。
-- ---------------------------------------------------------------------------

-- 1. 為既有 SKU 建立庫存。
--    秒殺用的 SKU 給較大的期初量，讓劃撥後仍有可售餘額——
--    這樣一般下單與秒殺可以同時被驗證，而不是二選一。
INSERT INTO inventory (sku_id, available, allocated)
SELECT s.id,
       CASE WHEN EXISTS (SELECT 1 FROM seckill_activity a WHERE a.sku_id = s.id)
                THEN 5000
            ELSE 200 END,
       0
FROM sku s;

-- 2. 期初建帳流水。
--    流水必須能重建出現在的數字，因此期初量本身也要有憑據——
--    少了這一筆，對帳算出來的「所有異動總和」永遠對不上實際的 available。
INSERT INTO inventory_movement (sku_id, type, available_delta, allocated_delta, ref_type, ref_no)
SELECT i.sku_id, 'ADJUST', i.available, 0, 'MANUAL', 'SEED'
FROM inventory i;

-- 3. 既有活動的劃撥流水。
INSERT INTO inventory_movement (sku_id, type, available_delta, allocated_delta, ref_type, ref_no)
SELECT a.sku_id, 'ALLOCATE', -a.total_stock, a.total_stock, 'ACTIVITY', CAST(a.id AS CHAR)
FROM seckill_activity a
WHERE EXISTS (SELECT 1 FROM inventory i WHERE i.sku_id = a.sku_id);

-- 4. 把劃撥套用到數字上，讓 inventory 與剛寫下的流水一致。
UPDATE inventory i
    JOIN (SELECT sku_id, SUM(total_stock) AS allocated_total
          FROM seckill_activity
          GROUP BY sku_id) a ON a.sku_id = i.sku_id
SET i.available = i.available - a.allocated_total,
    i.allocated = i.allocated + a.allocated_total;
