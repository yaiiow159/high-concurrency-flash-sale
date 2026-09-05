package com.flashsale.application.port.in.dto;

import com.flashsale.domain.promotion.Promotion;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 領券中心上的一張券。
 *
 * @param claimed 這個人已經領過了。<b>領過的仍然要顯示</b>，
 *                只是按鈕變成「已領取」——把它從清單裡拿掉的話，
 *                使用者會以為活動結束了，然後跑去問客服
 */
public record ClaimableCouponView(
        Long promotionId,
        String name,
        String rule,
        BigDecimal threshold,
        BigDecimal value,
        BigDecimal maxDiscount,
        Instant endAt,
        boolean claimed
) {

    public static ClaimableCouponView of(Promotion promotion, boolean claimed) {
        return new ClaimableCouponView(
                promotion.id(), promotion.name(), promotion.rule().name(),
                promotion.threshold(), promotion.value(), promotion.maxDiscount(),
                promotion.endAt(), claimed);
    }
}
