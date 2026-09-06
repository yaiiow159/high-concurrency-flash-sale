package com.flashsale.application.port.in.dto;

import com.flashsale.domain.promotion.Promotion;

import java.math.BigDecimal;

/** 可以用積分兌換的券。 */
public record ExchangeableCouponView(
        Long promotionId,
        String name,
        String rule,
        BigDecimal threshold,
        BigDecimal value,
        BigDecimal maxDiscount,
        long pointCost,
        boolean affordable
) {

    public static ExchangeableCouponView of(Promotion promotion, long pointBalance) {
        long cost = promotion.pointCost() == null ? 0L : promotion.pointCost();
        return new ExchangeableCouponView(
                promotion.id(), promotion.name(), promotion.rule().name(),
                promotion.threshold(), promotion.value(), promotion.maxDiscount(),
                cost, pointBalance >= cost);
    }
}
