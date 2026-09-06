package com.flashsale.domain.promotion;

/** 折抵怎麼從金額算出來。 */
public enum PromotionRule {

    /** 固定金額折抵：滿 1000 折 100 的那個 100。 */
    FIXED_AMOUNT,

    /** 比例折抵：0.2 代表折抵 20%（打八折）。 */
    PERCENTAGE
}
