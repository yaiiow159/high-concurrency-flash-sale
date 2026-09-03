package com.flashsale.domain.notification;

/**
 * 通知的送達狀態。
 *
 * <pre>
 *   PENDING ──markSent()──────────▶ SENT          (終態)
 *      │  ▲
 *      │  └──markFailed()────────▶ FAILED         (可重試，非終態)
 *      │                             │
 *      └──markUndeliverable()────────┴──────────▶ UNDELIVERABLE (終態)
 * </pre>
 *
 * <p><b>{@code FAILED} 刻意不是終態。</b>寄信失敗最常見的成因是 SMTP 暫時故障，
 * 而重試就會成功。把它做成終態，等於讓一次網路抖動永久吞掉一封通知。
 *
 * <p>與 {@code ShipmentStatus.FAILED} 同一個判斷：失敗之後的正常後續是再試一次，
 * 那就不該是終點。
 */
public enum NotificationStatus {

    /** 等待寄送。站內信不會出現這個狀態。 */
    PENDING,

    /** 已送出。 */
    SENT,

    /** 寄送失敗，可重試。 */
    FAILED,

    /**
     * 確定寄不出去（終態）。
     *
     * <p>與 {@code FAILED} 分開是必要的：信箱不存在、使用者已刪除這類原因
     * 重試一萬次都是同樣結果，混在 {@code FAILED} 裡會讓排程
     * <b>每一輪都白撈一次</b>，而且真正該重試的那些被稀釋在裡面。
     *
     * <p>紀錄仍然留著並帶失敗原因——刪掉會讓
     * 「為什麼這個人沒收到信」變成無解的問題。
     */
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
