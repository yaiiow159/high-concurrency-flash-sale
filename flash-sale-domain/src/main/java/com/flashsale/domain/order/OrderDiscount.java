package com.flashsale.domain.order;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.util.Objects;

/** 訂單上的一筆折扣<b>快照</b>。 */
public record OrderDiscount(
        String sourceType,
        Long sourceId,
        String name,
        BigDecimal amount
) {

    public OrderDiscount {
        Objects.requireNonNull(sourceType, "sourceType 不可為 null");
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "折扣名稱不可為空");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "折抵金額必須為正數");
        }
    }

    /** 運費折抵的 {@code sourceType}。與 {@code DiscountType.SHIPPING} 的名稱一致。 */
    private static final String SHIPPING = "SHIPPING";

    /** 這筆折抵是折運費還是折商品。 */
    public boolean appliesToShipping() {
        return SHIPPING.equals(sourceType);
    }
}
