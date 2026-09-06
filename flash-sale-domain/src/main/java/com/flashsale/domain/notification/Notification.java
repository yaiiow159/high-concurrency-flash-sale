package com.flashsale.domain.notification;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/** 通知聚合根。 */
public final class Notification {

    private static final int MAX_TITLE_LENGTH = 128;
    private static final int MAX_BODY_LENGTH = 1024;

    private final Long id;
    private final Long userId;
    private final NotificationChannel channel;
    private final NotificationType type;
    private final String title;
    private final String body;
    /** 關聯的業務單號（訂單號或退貨單號），供畫面連回去。 */
    private final String referenceNo;
    /** 來源事件的 ID，同時是冪等鍵。 */
    private final String sourceEventId;
    private final Instant createdAt;

    private NotificationStatus status;
    private String recipient;
    private String failureReason;
    private Instant sentAt;
    private Instant readAt;
    private int attemptCount;
    private final long version;

    private Notification(Long id, Long userId, NotificationChannel channel, NotificationType type,
                         String title, String body, String referenceNo, String sourceEventId,
                         NotificationStatus status, String recipient, String failureReason,
                         Instant createdAt, Instant sentAt, Instant readAt,
                         int attemptCount, long version) {
        this.id = id;
        this.userId = Objects.requireNonNull(userId, "userId 不可為 null");
        this.channel = Objects.requireNonNull(channel, "channel 不可為 null");
        this.type = Objects.requireNonNull(type, "type 不可為 null");
        this.title = requireText(title, MAX_TITLE_LENGTH, "標題");
        this.body = requireText(body, MAX_BODY_LENGTH, "內容");
        this.referenceNo = referenceNo;
        this.sourceEventId = requireText(sourceEventId, 64, "來源事件 ID");
        this.status = Objects.requireNonNull(status, "status 不可為 null");
        this.recipient = recipient;
        this.failureReason = failureReason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不可為 null");
        this.sentAt = sentAt;
        this.readAt = readAt;
        this.attemptCount = attemptCount;
        this.version = version;
    }

    /** 建立通知。 */
    public static Notification compose(Long userId, NotificationChannel channel,
                                       NotificationType type, String title, String body,
                                       String referenceNo, String sourceEventId, Instant now) {
        boolean pending = channel.requiresDelivery();
        return new Notification(null, userId, channel, type, title, body, referenceNo,
                sourceEventId,
                pending ? NotificationStatus.PENDING : NotificationStatus.SENT,
                null, null, now, pending ? null : now, null, 0, 0L);
    }

    public static Notification restore(Long id, Long userId, NotificationChannel channel,
                                       NotificationType type, String title, String body,
                                       String referenceNo, String sourceEventId,
                                       NotificationStatus status, String recipient,
                                       String failureReason, Instant createdAt, Instant sentAt,
                                       Instant readAt, int attemptCount, long version) {
        return new Notification(id, userId, channel, type, title, body, referenceNo,
                sourceEventId, status, recipient, failureReason, createdAt, sentAt,
                readAt, attemptCount, version);
    }

    /** 標記已寄出，並記下實際寄到哪裡。 */
    public void markSent(String actualRecipient, Instant now) {
        transitionTo(NotificationStatus.SENT);
        this.recipient = requireText(actualRecipient, 255, "收件地址");
        this.failureReason = null;
        this.sentAt = now;
        this.attemptCount++;
    }

    /** 暫時性失敗，下一輪會再試。 */
    public void markFailed(String reason, Instant now) {
        transitionTo(NotificationStatus.FAILED);
        this.failureReason = reason;
        this.attemptCount++;
    }

    /** 確定寄不出去，不再重試。 */
    public void markUndeliverable(String reason, Instant now) {
        transitionTo(NotificationStatus.UNDELIVERABLE);
        this.failureReason = reason;
        this.attemptCount++;
    }

    /** 標記已讀。 */
    public void markRead(Instant now) {
        if (channel != NotificationChannel.IN_APP) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "只有站內信有已讀狀態；Email 是否被讀取我們無從得知");
        }
        if (readAt == null) {
            this.readAt = now;
        }
    }

    public boolean belongsTo(Long candidateUserId) {
        return userId.equals(candidateUserId);
    }

    public boolean isUnread() {
        return channel == NotificationChannel.IN_APP && readAt == null;
    }

    private void transitionTo(NotificationStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "通知無法從 %s 轉為 %s".formatted(status, target));
        }
        this.status = target;
    }

    private static String requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, field + "不可為空");
        }
        if (value.length() > maxLength) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "%s不可超過 %d 字".formatted(field, maxLength));
        }
        return value;
    }

    public Long id() {
        return id;
    }

    public Long userId() {
        return userId;
    }

    public NotificationChannel channel() {
        return channel;
    }

    public NotificationType type() {
        return type;
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }

    public String referenceNo() {
        return referenceNo;
    }

    public String sourceEventId() {
        return sourceEventId;
    }

    public NotificationStatus status() {
        return status;
    }

    public String recipient() {
        return recipient;
    }

    public String failureReason() {
        return failureReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant sentAt() {
        return sentAt;
    }

    public Instant readAt() {
        return readAt;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public long version() {
        return version;
    }
}
