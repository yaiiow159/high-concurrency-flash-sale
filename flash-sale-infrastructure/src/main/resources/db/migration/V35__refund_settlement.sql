-- 退款的「已核可」與「錢已出去」分開記（ADR-0031）
--
-- 先前退貨單在發起退款的當下就寫成 REFUNDED，而閘道呼叫是之後才發生的。
-- 閘道失敗時帳上說已退、錢沒送出，且沒有任何查詢找得出這種單子——
-- 訊息重試 7.5 秒後進死信，而死信沒有補送路徑。

ALTER TABLE return_request
    ADD COLUMN refund_started_at DATETIME(3) NULL
        COMMENT '退款發起時間。錢還沒出去，到帳時間看 refunded_at' AFTER received_at,
    MODIFY COLUMN status VARCHAR(16) NOT NULL
        COMMENT 'REQUESTED/APPROVED/RECEIVED/REFUNDING/REFUNDED/REJECTED/CANCELLED';

-- 既有資料一律是舊語意下的 REFUNDED（錢已經退掉了），發起時間用退款時間回填。
-- 留 NULL 的話補送排程會把它們當成「發起於紀元零點」而全部重推一次。
UPDATE return_request SET refund_started_at = refunded_at WHERE refunded_at IS NOT NULL;

-- 補送排程的工作集：REFUNDING 且發起超過寬限期。正常情況下這個集合是空的，
-- 但閘道故障時它會瞬間長到幾千筆，那時沒有索引就是每分鐘一次全表掃加 filesort
CREATE INDEX idx_return_refunding ON return_request (status, refund_started_at);
