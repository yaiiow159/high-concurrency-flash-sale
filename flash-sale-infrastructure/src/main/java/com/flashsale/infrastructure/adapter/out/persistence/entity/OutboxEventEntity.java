package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 發件匣（Outbox）紀錄。
 *
 * <p>這張表讓「業務狀態變更」與「事件投遞」變成同一次 commit 的兩張表，
 * 因而天然原子——這正是本專案不需要 Seata 等分散式交易框架的原因（見 ADR-0004）。
 *
 * <p>投遞狀態機：{@code PENDING → PUBLISHED}，失敗則累加 {@code retry_count}，
 * 超過上限轉 {@code DEAD} 等待人工處理。
 */
@Entity
@Table(
        name = "outbox_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_event_id", columnNames = "event_id"),
        indexes = @Index(name = "idx_status_created", columnList = "status,created_at"))
public class OutboxEventEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DEAD = "DEAD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64, updatable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private String eventType;

    /** 聚合根 id，投遞時作為 MQ 分區鍵，保證同一訂單的事件不會亂序。 */
    @Column(name = "aggregate_id", nullable = false, length = 64, updatable = false)
    private String aggregateId;

    @Lob
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payload;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEventEntity() {
        // JPA 專用
    }

    public OutboxEventEntity(String eventId, String eventType, String aggregateId,
                             String payload, Instant createdAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.status = STATUS_PENDING;
        this.retryCount = 0;
        this.createdAt = createdAt;
    }

    public void markPublished(Instant publishedAt) {
        this.status = STATUS_PUBLISHED;
        this.publishedAt = publishedAt;
        this.lastError = null;
    }

    /** 投遞失敗；達到重試上限後轉為 DEAD，停止無止境重試並讓告警能抓到。 */
    public void markFailed(String error, int maxRetry) {
        this.retryCount++;
        this.lastError = truncate(error);
        if (this.retryCount >= maxRetry) {
            this.status = STATUS_DEAD;
        }
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 512 ? error : error.substring(0, 512);
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getPayload() {
        return payload;
    }

    public String getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
