package com.flashsale.domain.payment;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 付款狀態機。
 *
 * <pre>
 *   PENDING ──成功──► SUCCEEDED ──無法入帳──► REFUND_PENDING ──► REFUNDED
 *      │  ▲                                （訂單已被關閉）
 *      │  │
 *      └──┴─失敗──► FAILED ──重試──► PENDING
 * </pre>
 *
 * <p><b>{@code SUCCEEDED → REFUND_PENDING} 是這個狀態機最重要的一條轉移。</b>
 * 它對應一個真實的競態：使用者完成付款的同時，逾時關單排程正好把訂單取消。
 *
 * <p>此時錢<b>確實收了</b>，因此絕不能把付款標記為失敗——那會讓帳目與現實脫節，
 * 對帳時看到的是「沒收到錢」，但銀行那邊是收到的。
 * 正確做法是誠實記錄「收款成功、但無法入帳」，再走退款流程。
 */
public enum PaymentStatus {

    /** 已建立，等待閘道回覆。 */
    PENDING,

    /** 收款成功。 */
    SUCCEEDED,

    /** 收款失敗，可重新發起。 */
    FAILED,

    /**
     * 收款成功但無法入帳，待退款。
     *
     * <p>唯一的成因是「付款完成時訂單已被關閉」。
     * 這個狀態必須能被監控抓到——它代表有一筆錢暫時卡在系統裡。
     */
    REFUND_PENDING,

    /** 已退款。 */
    REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS = Map.of(
            PENDING, EnumSet.of(SUCCEEDED, FAILED),
            // 失敗後允許重新發起，讓使用者能換一張卡再試
            FAILED, EnumSet.of(PENDING),
            SUCCEEDED, EnumSet.of(REFUND_PENDING),
            REFUND_PENDING, EnumSet.of(REFUNDED),
            REFUNDED, Collections.emptySet()
    );

    public boolean canTransitionTo(PaymentStatus target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /** 錢是否已經收到（含後續待退或已退的情況）。 */
    public boolean moneyReceived() {
        return this == SUCCEEDED || this == REFUND_PENDING || this == REFUNDED;
    }

    /** 是否需要人為或流程介入。 */
    public boolean requiresAttention() {
        return this == REFUND_PENDING;
    }
}
