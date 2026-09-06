package com.flashsale.domain.promotion;

/** 折扣的來源類型。 */
public enum DiscountType {

    /** 單品折扣：改變某一行的實付單價。 */
    ITEM_DISCOUNT,

    /** 訂單折扣：滿減、整單折扣，對折後小計計算。 */
    ORDER_DISCOUNT,

    /** 優惠券。 */
    COUPON,

    /** 運費折抵。最後算，因為免運門檻看的是折後金額。 */
    SHIPPING
}
