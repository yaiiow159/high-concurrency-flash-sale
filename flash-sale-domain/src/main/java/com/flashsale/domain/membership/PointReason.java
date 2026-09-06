package com.flashsale.domain.membership;

/** 積分異動的原因。 */
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
