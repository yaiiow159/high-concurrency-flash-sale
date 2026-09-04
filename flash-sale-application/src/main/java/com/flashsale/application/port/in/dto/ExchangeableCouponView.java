package com.flashsale.application.port.in.dto;

import com.flashsale.domain.promotion.Promotion;

import java.math.BigDecimal;

/**
 * 可以用積分兌換的券。
 *
 * <p>{@code affordable} 由後端算。前端拿餘額與價格自己比也行，
 * 但那個判斷會出現在三個地方（按鈕禁用、樣式、提示文字），
 * 而三處遲早會有一處寫成 {@code >} 而不是 {@code >=}。
 */
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
