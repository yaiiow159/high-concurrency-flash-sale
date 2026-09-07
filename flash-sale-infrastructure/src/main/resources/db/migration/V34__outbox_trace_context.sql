-- Outbox 兩側接續 trace（ADR-0029）。
-- 事件先落 DB、由排程在另一個執行緒搬到 Kafka，observation 的自動傳播在那裡會斷。
-- 寫入時把當下的 W3C traceparent 存下來，中繼時還原，整條鏈才是同一個 trace id。
-- 可為 NULL：排程或維運工具寫入的事件沒有上游 trace。
ALTER TABLE outbox_event
    ADD COLUMN trace_context VARCHAR(128) NULL COMMENT '寫入時的 W3C traceparent，中繼時還原';
