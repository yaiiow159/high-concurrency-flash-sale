package com.flashsale.domain.stock;

/**
 * 庫存對帳的判定結果。
 *
 * <p>對帳依據的恆等式：
 * <pre>
 *   Redis 餘量 + Σ(未取消訂單的數量) = 活動總庫存
 * </pre>
 *
 * <p>把偏差方向翻譯成業務語意，而不是丟一個正負數給值班的人自己推——
 * 半夜三點看到告警時，「有庫存被鎖住」遠比「drift = -37」好懂。
 */
public enum ReconciliationVerdict {

    /** 恆等式成立，帳是平的。 */
    BALANCED,

    /**
     * Redis 餘量<b>少於</b>應有值：有庫存被扣掉卻找不到對應訂單。
     *
     * <p>後果是<b>少賣</b>——商品顯示售罄但實際有貨。損失營收但不會產生無法履約的訂單，
     * 且可透過退回孤兒扣減自動修復。
     */
    STOCK_LEAKED,

    /**
     * Redis 餘量<b>多於</b>應有值：訂單佔用的量沒有反映在 Redis 上。
     *
     * <p>後果是<b>超賣</b>——會賣出不存在的商品，不可逆。
     * 這是所有對帳結果中最嚴重的一種，必須立即人工介入，
     * <b>不可自動修復</b>：自動下修餘量會讓正在進行中的合法請求無故失敗。
     */
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
