package com.flashsale.domain.fulfillment;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

/**
 * 出貨單號。
 *
 * <p>與 {@code OrderNo}、{@code PaymentNo} 同樣包成值物件，理由也相同：
 * 三者都是字串型別的識別碼，裸著傳遞遲早會有人把訂單號填進出貨單號的位置——
 * 而那個錯誤在編譯期完全看不出來，只會在查無資料時表現成一個莫名其妙的 404。
 */
public record ShipmentNo(String value) {

    public ShipmentNo {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "出貨單號不可為空");
        }
    }

    public static ShipmentNo of(String value) {
        return new ShipmentNo(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
