package com.flashsale.domain.promotion;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.util.Objects;

/** 一筆已套用的折扣。 */
public record AppliedDiscount(
        DiscountType type,
        Long sourceId,
        String name,
        BigDecimal amount
) {

    public AppliedDiscount {
        Objects.requireNonNull(type, "type 不可為 null");
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "折扣名稱不可為空");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "折抵金額必須為正數，用負數表示折扣遲早會有人把符號弄反");
        }
    }
}
