package com.flashsale.application.port.in.dto;

import com.flashsale.domain.promotion.DiscountType;
import com.flashsale.domain.promotion.Promotion;
import com.flashsale.domain.promotion.PromotionRule;

import java.math.BigDecimal;
import java.time.Instant;

/** 建立或修改優惠的輸入。驗證交給 {@link Promotion} 的建構子，這裡不重複一份。 */
public record PromotionCommand(
        String name,
        DiscountType type,
        PromotionRule rule,
        BigDecimal threshold,
        BigDecimal value,
        BigDecimal maxDiscount,
        Long pointCost,
        Instant startAt,
        Instant endAt,
        boolean enabled
) {

    public Promotion toPromotion(Long id) {
        return Promotion.of(id, name, type, rule, threshold, value, maxDiscount,
                startAt, endAt, enabled, pointCost);
    }
}
