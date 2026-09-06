package com.flashsale.domain.notification;

/** 通知的送達狀態。 */
public enum NotificationStatus {

    /** 等待寄送。站內信不會出現這個狀態。 */
    PENDING,

    /** 已送出。 */
    SENT,

    /** 寄送失敗，可重試。 */
    FAILED,

    /** 確定寄不出去（終態）。 */
    UNDELIVERABLE;

    public boolean canTransitionTo(NotificationStatus target) {
        return switch (this) {
            case PENDING -> target == SENT || target == FAILED || target == UNDELIVERABLE;
            // 失敗後可能重試成功、再次失敗，或被判定為永久寄不出去
            case FAILED -> target == SENT || target == FAILED || target == UNDELIVERABLE;
            case SENT, UNDELIVERABLE -> false;
        };
    }

    /** 是否還需要排程再試一次。 */
    public boolean awaitingDelivery() {
        return this == PENDING || this == FAILED;
    }
}
