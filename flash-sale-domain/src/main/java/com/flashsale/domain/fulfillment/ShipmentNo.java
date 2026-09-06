package com.flashsale.domain.fulfillment;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

/** 出貨單號。 */
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
