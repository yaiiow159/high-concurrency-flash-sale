package com.flashsale.domain.promotion;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.util.Objects;

/** 要計價的一個品項。 */
public record PricedItem(
        Long skuId,
        BigDecimal unitPrice,
        int quantity,
        Long sourceActivityId
) {

    public PricedItem {
        Objects.requireNonNull(skuId, "skuId 不可為 null");
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "單價不可為負數");
        }
        if (quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "數量必須大於 0");
        }
    }

    public BigDecimal subtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public boolean isFromSeckill() {
        return sourceActivityId != null;
    }
}
