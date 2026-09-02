package com.flashsale.domain.fulfillment;

/**
 * 承運商。
 *
 * <p>做成列舉而非自由字串：物流單號的查詢網址依承運商而異，
 * 自由字串會讓「黑貓」「黑貓宅急便」「TCAT」變成三個不同的承運商，
 * 而查詢連結只能對其中一個生效。
 *
 * <p>新增承運商是低頻的營運動作（一年可能一次），
 * 為此改一次程式碼並重新部署是划算的——換來的是查詢連結永遠正確。
 */
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
