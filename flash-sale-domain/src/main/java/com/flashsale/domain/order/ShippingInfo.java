package com.flashsale.domain.order;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

/**
 * 訂單裡的收貨資訊——<b>快照，不是引用</b>。
 *
 * <p>這是整個訂單模型裡最容易被做錯的一個欄位。直覺的做法是在訂單上存 {@code addressId}，
 * 顯示時再去地址簿查——那個做法在使用者搬家的那一刻就壞了：
 * 三個月前已送達的訂單會顯示成寄到新家，出貨紀錄與客訴處理的依據被靜靜竄改。
 *
 * <p>與訂單行的商品快照、單價快照是同一條原則（見 {@link OrderLine} 與 ADR-0007）：
 * <b>訂單記錄的是「當時發生了什麼」，不是「現在的資料長什麼樣」。</b>
 *
 * <p>因此這個型別是 record（不可變），且 {@code OrderEntity} 以
 * {@code updatable = false} 鎖住對應欄位。那不是裝飾——
 * 一旦有人寫出「訂單建立後更新地址」的程式碼，快照就退化成了引用。
 *
 * <p>要改地址只有一種正確作法：<b>取消原訂單，重新下單。</b>
 * 那會留下兩筆可追溯的紀錄，而不是一筆被覆寫過的。
 *
 * <p>保留結構化欄位而非只存一個字串，是為了物流介接——
 * 超商取貨與宅配 API 要的是分開的縣市與區，事後從一整串地址切回來
 * 是猜測，不是解析。
 */
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

    /**
     * 遮蔽後的電話，供日誌與客服介面使用。
     *
     * <p>與 {@code Email} 的遮蔽同理：可以用來核對「是不是這一筆」，
     * 但看到日誌的人拿不到完整號碼。
     */
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
