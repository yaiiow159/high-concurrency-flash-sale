package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 通知。
 *
 * <p>{@code title}、{@code body} 與 {@code sourceEventId} 都是
 * {@code updatable = false}。前兩者是寄送內容的快照——可改就等於
 * 「我們對使用者說過什麼」可以被事後改寫；後者是冪等鍵，改了就失去作用。
 */
@Entity
@Table(name = "notification")
public class NotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "channel", nullable = false, length = 16, updatable = false)
    private String channel;

    @Column(name = "type", nullable = false, length = 24, updatable = false)
    private String type;

    @Column(name = "title", nullable = false, length = 128, updatable = false)
    private String title;

    @Column(name = "body", nullable = false, length = 1024, updatable = false)
    private String body;

    @Column(name = "reference_no", length = 64, updatable = false)
    private String referenceNo;

    @Column(name = "source_event_id", nullable = false, length = 64, updatable = false)
    private String sourceEventId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "recipient", length = 255)
    private String recipient;

    @Column(name = "failure_reason", length = 256)
    private String failureReason;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected NotificationEntity() {
        // JPA 專用
    }

    public NotificationEntity(Long userId, String channel, String type, String title, String body,
                              String referenceNo, String sourceEventId, String status,
                              Instant createdAt, Instant sentAt) {
        this.userId = userId;
        this.channel = channel;
        this.type = type;
        this.title = title;
        this.body = body;
        this.referenceNo = referenceNo;
        this.sourceEventId = sourceEventId;
        this.status = status;
        this.createdAt = createdAt;
        this.sentAt = sentAt;
        this.attemptCount = 0;
    }

    public void applyStateChange(String status, String recipient, String failureReason,
                                 Instant sentAt, Instant readAt, int attemptCount) {
        this.status = status;
        this.recipient = recipient;
        this.failureReason = failureReason;
        this.sentAt = sentAt;
        this.readAt = readAt;
        this.attemptCount = attemptCount;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getChannel() {
        return channel;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getReferenceNo() {
        return referenceNo;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public String getStatus() {
        return status;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public long getVersion() {
        return version;
    }
}
