package com.flashsale.domain.shipping;

/** 配送方式。 */
public enum ShippingMethod {

    /** 宅配到府。 */
    HOME_DELIVERY("宅配到府"),

    /** 超商取貨。 */
    CVS_PICKUP("超商取貨");

    private final String displayName;

    ShippingMethod(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
