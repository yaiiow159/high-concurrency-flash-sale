package com.flashsale.domain.promotion;

/** 券的狀態。 */
public enum CouponStatus {

    /** 已發放，尚未使用。 */
    ISSUED,

    /**
     * 已核銷。
     *
     * <p>終態。券的核銷與訂單建立在<b>同一個交易</b>裡——
     * 分開做的話，先核銷後建單失敗會讓券白白消失，
     * 反過來則是訂單享受了折扣但券還在（ADR-0013 決策 7）。
     */
    USED,

    /** 已過期。由排程或查詢時判定，不是使用者的動作。 */
    EXPIRED
}
