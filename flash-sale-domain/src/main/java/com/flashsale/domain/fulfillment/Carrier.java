package com.flashsale.domain.fulfillment;

/** 承運商。 */
public enum Carrier {

    /** 黑貓宅急便。 */
    TCAT("黑貓宅急便", "https://www.t-cat.com.tw/inquire/trace.aspx?no="),

    /** 新竹物流。 */
    HCT("新竹物流", "https://www.hct.com.tw/Search/SearchGoods_Its.aspx?no="),

    /** 中華郵政。 */
    POST("中華郵政", "https://postserv.post.gov.tw/pstmail/main_mail.html?no="),

    /** 超商取貨。 */
    CVS("超商取貨", ""),

    /** 自行配送，無外部追蹤連結。 */
    SELF("自行配送", "");

    private final String displayName;
    private final String trackingUrlPrefix;

    Carrier(String displayName, String trackingUrlPrefix) {
        this.displayName = displayName;
        this.trackingUrlPrefix = trackingUrlPrefix;
    }

    public String displayName() {
        return displayName;
    }

    /** 追蹤網址；沒有外部查詢系統的承運商回傳 {@code null} 而非空字串或假連結。 */
    public String trackingUrl(String trackingNumber) {
        return trackingUrlPrefix.isEmpty() ? null : trackingUrlPrefix + trackingNumber;
    }
}
