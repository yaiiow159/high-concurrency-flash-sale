package com.flashsale.application.port.in.dto;

import com.flashsale.domain.promotion.Coupon;
import com.flashsale.domain.promotion.Promotion;

import java.math.BigDecimal;
import java.time.Instant;

/** 使用者手上的一張券。 */
public record CouponView(
        Long id,
        String code,
        String name,
        String rule,
        BigDecimal threshold,
        BigDecimal value,
        BigDecimal maxDiscount,
        Instant expiresAt
) {

    public static CouponView of(Coupon coupon, Promotion promotion) {
        return new CouponView(coupon.id(), coupon.code(), promotion.name(),
                promotion.rule().name(), promotion.threshold(), promotion.value(),
                promotion.maxDiscount(), coupon.expiresAt());
    }
}
