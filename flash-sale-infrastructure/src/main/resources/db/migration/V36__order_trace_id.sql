-- 訂單記下建單當下的 trace id（ADR-0029）
--
-- 客服查「這張單為什麼卡住」時，要能從後台一鍵跳到 Tempo，
-- 而不是拿訂單號去日誌裡撈。可為 NULL：排程或維運工具建的單沒有上游 trace。
ALTER TABLE orders
    ADD COLUMN trace_id VARCHAR(32) NULL COMMENT '建單當下的 W3C trace id，供後台跳轉追蹤' AFTER close_reason;
