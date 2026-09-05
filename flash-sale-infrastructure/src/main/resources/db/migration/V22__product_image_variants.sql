-- 圖片變體是否已產生（ADR-0027 決策 4）。
--
-- 縮圖在**慢車道**做，所以掛上圖之後有一小段時間變體還不存在。
-- 少了這個旗標，前端只有兩條路：猜變體的網址然後靠 onerror 退回原圖
-- （每張圖多一次失敗的請求），或是永遠用原圖（縮圖等於白做）。
--
-- 存旗標讓 API 能直接回「現在最好的那一個網址」，前端不必知道
-- 變體是怎麼命名的——那是後端的實作細節。
ALTER TABLE product_image
    ADD COLUMN variants_ready TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '尺寸變體是否已產生；WebP 與過小的圖會永遠是 0' AFTER byte_size;

-- 既有的圖片還沒有變體。標成 0 讓它們沿用原圖，
-- 而下一次重新掛載時消費端會補上——不做一次性的批次回填：
-- 那需要一支只跑一次的腳本，而它跑失敗時沒有人會發現。
UPDATE product_image SET variants_ready = 0;
