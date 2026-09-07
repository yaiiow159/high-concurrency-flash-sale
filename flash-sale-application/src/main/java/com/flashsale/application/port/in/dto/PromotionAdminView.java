package com.flashsale.application.port.in.dto;

import com.flashsale.application.port.out.PromotionRepository.CouponStats;
import com.flashsale.domain.promotion.Promotion;

import java.math.BigDecimal;
import java.time.Instant;

/** 後台看到的優惠：規則本身加上發券／核銷的數字。 */
public record PromotionAdminView(
        Long id,
        String name,
        String type,
        String rule,
        BigDecimal threshold,
        BigDecimal value,
        BigDecimal maxDiscount,
        Long pointCost,
        Instant startAt,
        Instant endAt,
        boolean enabled,
        long issuedCoupons,
        long usedCoupons
) {

    public static PromotionAdminView from(Promotion promotion, CouponStats stats) {
        CouponStats safe = stats == null ? new CouponStats(0, 0) : stats;
        return new PromotionAdminView(
                promotion.id(), promotion.name(), promotion.type().name(), promotion.rule().name(),
                promotion.threshold(), promotion.value(), promotion.maxDiscount(),
                promotion.pointCost(), promotion.startAt(), promotion.endAt(), promotion.enabled(),
                safe.issued(), safe.used());
    }
}
