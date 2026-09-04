package com.flashsale.domain.shipping;

/**
 * 配送方式。
 *
 * <p>目前只有宅配。超商取貨是下一份 ADR（0020），
 * 因為它帶來一個新的訂單終態（逾期未取）而不只是另一組費率——
 * 那比查表複雜得多，值得單獨做一次。
 *
 * <p>列舉先留兩個值，讓費率表與 API 契約不必在下一步改動。
 */
public enum ShippingMethod {

    /** 宅配到府。 */
    HOME_DELIVERY("宅配到府"),

    /**
     * 超商取貨。
     *
     * <p><b>尚未實作</b>——費率表裡沒有它的資料，選了會查不到費率。
     * 保留這個值是為了讓下一步不必改 API 契約。
     */
    CVS_PICKUP("超商取貨");

    private final String displayName;

    ShippingMethod(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
