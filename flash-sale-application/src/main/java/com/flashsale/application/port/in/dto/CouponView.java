package com.flashsale.application.port.in.dto;

import com.flashsale.domain.promotion.Coupon;
import com.flashsale.domain.promotion.Promotion;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 使用者手上的一張券。
 *
 * <p>把 {@link Coupon}（是誰的、能不能用）與 {@link Promotion}（折多少）
 * 合起來給前端。這兩者在領域層刻意分開——同一個優惠發給一千個人時
 * 不必複製一千份規則——但畫面上它們是同一張券。
 */
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
