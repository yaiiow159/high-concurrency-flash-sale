-- 退貨申請的冪等鍵。
--
-- 少了它，使用者在網路逾時後再按一次送出，同一批商品就會被申請兩次退貨——
-- 而「累計退款 ≤ 已付金額」那道上限管的是金額總和，不是每個 SKU 的數量，
-- 所以攔不到「退兩次 A、都不退 B」這種組合。
--
-- 這是第 4 條鐵則（冪等是三層）在退貨路徑上的補課：
-- 應用層查 request_id、這道唯一索引兜底，兩者缺一都不夠。

-- 既有資料補一個以單號推導的值。用 MIG- 前綴而非隨機值，
-- 保持可追溯（看得出這筆是遷移補的，不是使用者送來的）。
ALTER TABLE return_request
    ADD COLUMN request_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '冪等鍵' AFTER user_id;

UPDATE return_request SET request_id = CONCAT('MIG-', return_no) WHERE request_id = '';

-- 補完值才建唯一索引：先建的話既有資料的空字串會互相衝突
ALTER TABLE return_request
    ALTER COLUMN request_id DROP DEFAULT;

CREATE UNIQUE INDEX uk_return_request_id ON return_request (request_id);
