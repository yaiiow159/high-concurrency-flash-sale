package com.flashsale.domain.payment;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** 付款狀態機。 */
public enum PaymentStatus {

    /** 已建立，等待閘道回覆。 */
    PENDING,

    /** 收款成功。 */
    SUCCEEDED,

    /** 收款失敗，可重新發起。 */
    FAILED,

    /** 收款成功但無法入帳，待退款。 */
    REFUND_PENDING,

    /** 已部分退款，仍有餘額在帳上。 */
    PARTIALLY_REFUNDED,

    /** 已全額退款。 */
    REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS = Map.of(
            PENDING, EnumSet.of(SUCCEEDED, FAILED),
            // 失敗後允許重新發起，讓使用者能換一張卡再試
            FAILED, EnumSet.of(PENDING),
            SUCCEEDED, EnumSet.of(REFUND_PENDING, PARTIALLY_REFUNDED, REFUNDED),
            // 自我轉移是刻意的：一張訂單可以退很多次，每次退一部分。
            // 沒有它，第二次部分退款會被狀態機擋下來
            PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            REFUND_PENDING, EnumSet.of(REFUNDED),
            REFUNDED, Collections.emptySet()
    );

    public boolean canTransitionTo(PaymentStatus target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /** 錢是否已經收到（含後續待退、部分退、已退的情況）。 */
    public boolean moneyReceived() {
        return this == SUCCEEDED || this == REFUND_PENDING
                || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }

    /** 是否需要人為或流程介入。 */
    public boolean requiresAttention() {
        return this == REFUND_PENDING;
    }
}
