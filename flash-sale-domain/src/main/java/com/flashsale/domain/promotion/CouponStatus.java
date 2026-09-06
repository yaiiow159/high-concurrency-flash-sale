package com.flashsale.domain.promotion;

/** 券的狀態。 */
public enum CouponStatus {

    /** 已發放，尚未使用。 */
    ISSUED,

    /** 已核銷。 */
    USED,

    /** 已過期。由排程或查詢時判定，不是使用者的動作。 */
    EXPIRED
}
