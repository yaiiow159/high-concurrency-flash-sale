package com.flashsale.domain.membership;

/**
 * 積分異動的原因。
 *
 * <p>它同時是<b>冪等鍵的一部分</b>：`(user_id, reason, ref_no)` 唯一。
 * 因此同一張訂單可以有「完成入帳」與「退款扣回」兩筆而不衝突，
 * 但同一個原因對同一個單號只會有一筆——重放不會變成兩次入帳。
 */
public enum PointReason {

    /** 訂單送達入帳。ref_no 是訂單編號。 */
    ORDER_COMPLETED("訂單完成回饋"),

    /** 退款扣回。ref_no 是退貨單號——同一張訂單可能有多張退貨單。 */
    RETURN_CLAWBACK("退貨收回"),

    /** 兌換優惠券。ref_no 是券號。 */
    COUPON_EXCHANGE("兌換優惠券"),

    /** 人工調整。ref_no 由操作者指定，用於補償與更正。 */
    ADJUSTMENT("人工調整");

    private final String displayName;

    PointReason(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
