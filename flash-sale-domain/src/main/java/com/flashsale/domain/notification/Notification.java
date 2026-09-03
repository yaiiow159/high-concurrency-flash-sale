package com.flashsale.domain.notification;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/**
 * 通知聚合根。
 *
 * <h2>內容是快照，不是樣板引用</h2>
 *
 * <p>{@code title} 與 {@code body} 是<b>建立當下算好的文字</b>，
 * 不是「樣板代號 + 參數」。理由與 {@code OrderLine.skuSnapshot} 完全相同，
 * 而在這裡更絕對：
 *
 * <ul>
 *   <li>Email <b>已經寄出去了</b>。事後改樣板不會改變使用者信箱裡那封信，
 *       但會改變我們的紀錄——於是紀錄與現實不一致，客訴時我們說不出
 *       「當時到底寄了什麼」</li>
 *   <li>站內信同理：使用者三個月前看到的文字，不該因為我們改了措辭而變樣</li>
 * </ul>
 *
 * <p>代價是改樣板不會回溯套用到舊通知。那不是缺點，那正是要的效果。
 *
 * <h2>收件地址在寄送當下才決定，之後不可變</h2>
 *
 * <p>建立時只記 {@code userId}。信箱要等真的要寄的那一刻才從 Identity 取——
 * 使用者在通知排隊期間改了信箱，該寄到新的那個。
 *
 * <p>但<b>寄出之後 {@code recipient} 就固定了</b>：它是「這封信實際寄到哪裡」
 * 的紀錄，不是「這個人現在的信箱」。存成引用的話，使用者換信箱之後
 * 半年前的寄送紀錄會顯示成寄到新地址，而那是對帳與客訴依據被竄改。
 */
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
    /**
     * 來源事件的 ID，同時是冪等鍵。
     *
     * <p>Outbox 是至少一次語意，同一個事件一定會被重複投遞。
     * 少了它，使用者會為同一次出貨收到三封一樣的信。
     */
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

    /**
     * 建立通知。
     *
     * <p>站內信建立即為 {@code SENT}——它不經過任何外部系統，
     * 交易 commit 後使用者就看得到，多一個「發送中」狀態只是憑空製造
     * 一個永遠不會被觀察到的中間態。
     */
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

    /**
     * 標記已寄出，並記下實際寄到哪裡。
     *
     * <p>{@code recipient} 只在這一刻寫入且之後不再改動——它是寄送紀錄，
     * 不是使用者現在的信箱。
     */
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

    /**
     * 確定寄不出去，不再重試。
     *
     * <p>與 {@link #markFailed} 分成兩個方法而不是加一個 boolean 參數：
     * {@code markFailed(reason, now, true)} 的呼叫端讀不出那個 true 是什麼意思，
     * 而這兩者的差別（會不會再被撈取）在維運上是關鍵的。
     */
    public void markUndeliverable(String reason, Instant now) {
        transitionTo(NotificationStatus.UNDELIVERABLE);
        this.failureReason = reason;
        this.attemptCount++;
    }

    /**
     * 標記已讀。
     *
     * <p><b>重複標記不視為錯誤</b>，直接略過。使用者連點兩次、
     * 或兩個分頁同時開著同一封通知，都是完全正常的操作；
     * 為此拋例外只會讓畫面出現一個使用者無法理解的錯誤。
     */
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
