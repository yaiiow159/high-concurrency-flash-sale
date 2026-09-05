-- 放寬發件匣的聚合識別欄寬。
--
-- 原本是 VARCHAR(64)，註解寫「訂單號」——那不是一個經過設計的上限，
-- 而是「訂單號剛好放得下」。圖片變體事件的聚合識別是**物件鍵**
-- （{sha256}.{ext}，68 個字元），一掛圖就截斷失敗，
-- 而失敗點在 outbox 的 insert 上，錯誤訊息與圖片毫無關係。
--
-- 對齊 product_image.object_key 的 VARCHAR(128)：兩邊存的是同一個東西，
-- 寬度不同就是一個等著被踩的地雷。
--
-- **明確寫死 ALGORITHM 與 LOCK**，不讓 MySQL 自己挑：
-- outbox 是秒殺熱路徑每一筆訂單都要寫的表，預設可能挑到 COPY，
-- 而 COPY 會鎖住整張表重建。寫死之後若哪天這個操作不再支援線上進行，
-- 遷移會**當場失敗**——那遠好過在尖峰時段安靜地鎖表。
--
-- （INSTANT 在這裡不適用：它只支援加減欄位那類純中繼資料的變更，
-- 改欄位型別即使長度前綴不變也要走 INPLACE。實測回 ERROR 1845。）
ALTER TABLE outbox_event
    MODIFY COLUMN aggregate_id VARCHAR(128) NOT NULL
        COMMENT '聚合識別，作為 MQ 分區鍵；訂單事件放訂單號、圖片事件放物件鍵',
    ALGORITHM = INPLACE, LOCK = NONE;
