package com.flashsale.domain.shipping;

/** 配送區域。 */
public enum ShippingZone {

    /** 台灣本島。 */
    MAIN_ISLAND("本島"),

    /** 離島：澎湖、金門、馬祖、綠島、蘭嶼。 */
    OUTLYING_ISLAND("離島");

    private final String displayName;

    ShippingZone(String displayName) {
        this.displayName = displayName;
    }

    /** 從郵遞區號推導區域。 */
    public static ShippingZone fromPostalCode(String postalCode) {
        if (postalCode == null || postalCode.length() < 3) {
            return MAIN_ISLAND;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(postalCode.substring(0, 3));
        } catch (NumberFormatException notNumeric) {
            // 郵遞區號不是數字只可能是資料髒了。當本島處理，
            // 少收運費比讓使用者看到一個無法理解的金額好
            return MAIN_ISLAND;
        }

        boolean outlying =
                // 馬祖（連江縣）
                (prefix >= 209 && prefix <= 212)
                // 澎湖
                || (prefix >= 880 && prefix <= 885)
                // 金門
                || (prefix >= 890 && prefix <= 896)
                // 綠島、蘭嶼（台東縣轄下但需空運或船運）
                || prefix == 951 || prefix == 952;

        return outlying ? OUTLYING_ISLAND : MAIN_ISLAND;
    }

    public String displayName() {
        return displayName;
    }
}
