package com.flashsale.domain.order;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

/** 訂單裡的收貨資訊——<b>快照，不是引用</b>。 */
public record ShippingInfo(
        String recipientName,
        String phone,
        String postalCode,
        String region,
        String district,
        String streetAddress) {

    public ShippingInfo {
        requirePresent(recipientName, "收件人");
        requirePresent(phone, "聯絡電話");
        requirePresent(region, "縣市");
        requirePresent(district, "鄉鎮市區");
        requirePresent(streetAddress, "地址");
    }

    /** 供顯示與列印單據使用的完整地址。 */
    public String fullAddress() {
        return "%s%s%s%s".formatted(
                postalCode == null || postalCode.isBlank() ? "" : postalCode + " ",
                region, district, streetAddress);
    }

    /** 遮蔽後的電話，供日誌與客服介面使用。 */
    public String maskedPhone() {
        if (phone.length() <= 4) {
            return "*".repeat(phone.length());
        }
        return phone.substring(0, 2) + "*".repeat(phone.length() - 4)
                + phone.substring(phone.length() - 2);
    }

    private static void requirePresent(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, field + "不可為空");
        }
    }
}
