package com.flashsale.domain.shipping;

/**
 * 配送區域。
 *
 * <h2>由郵遞區號推導，不由使用者選</h2>
 *
 * <p>離島運費是本島的兩到三倍，而讓使用者自己選區域等於讓他選價格。
 * {@code ShippingInfo} 已經有郵遞區號，推導是純函式。
 *
 * <h2>推導不到就當本島</h2>
 *
 * <p>這個方向對商家不利（少收運費），但反過來（當成離島多收）
 * 會讓使用者在結帳頁看到一個他無法理解的金額——
 * 而那是一個他會直接關掉頁面的理由。
 */
public enum ShippingZone {

    /** 台灣本島。 */
    MAIN_ISLAND("本島"),

    /** 離島：澎湖、金門、馬祖、綠島、蘭嶼。 */
    OUTLYING_ISLAND("離島");

    private final String displayName;

    ShippingZone(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 從郵遞區號推導區域。
     *
     * <p>台灣的離島郵遞區號是固定的幾段，寫死在這裡而不是放設定檔——
     * 它們是地理事實，不是可調的參數。行政區調整是幾十年一次的事，
     * 而那時應該有一次明確的程式碼變更與一個測試。
     *
     * <p>只看前三碼：台灣的郵遞區號是 3+2 或 3+3 格式，
     * 而區域完全由前三碼決定。
     */
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
