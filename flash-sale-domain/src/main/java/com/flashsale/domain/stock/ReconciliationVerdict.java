package com.flashsale.domain.stock;

/** 庫存對帳的判定結果。 */
public enum ReconciliationVerdict {

    /** 恆等式成立，帳是平的。 */
    BALANCED,

    /** Redis 餘量<b>少於</b>應有值：有庫存被扣掉卻找不到對應訂單。 */
    STOCK_LEAKED,

    /** Redis 餘量<b>多於</b>應有值：訂單佔用的量沒有反映在 Redis 上。 */
    OVERSELL_RISK,

    /** Redis 中沒有此活動的庫存鍵：尚未預熱，或鍵已過期。無從比對。 */
    NOT_INITIALIZED;

    public boolean isBalanced() {
        return this == BALANCED;
    }

    /** 是否需要告警。未預熱不算異常（活動可能剛建立或早已結束）。 */
    public boolean requiresAttention() {
        return this == STOCK_LEAKED || this == OVERSELL_RISK;
    }

    /**
     * 依偏差量判定。
     *
     * @param drift {@code Redis 實際餘量 - 依訂單推算的應有餘量}
     */
    public static ReconciliationVerdict fromDrift(long drift) {
        if (drift == 0) {
            return BALANCED;
        }
        return drift < 0 ? STOCK_LEAKED : OVERSELL_RISK;
    }
}
