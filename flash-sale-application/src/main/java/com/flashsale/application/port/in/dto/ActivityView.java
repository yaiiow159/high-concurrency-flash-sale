package com.flashsale.application.port.in.dto;

import com.flashsale.domain.activity.SeckillActivity;

import java.math.BigDecimal;
import java.time.Instant;

/** 活動詳情，供商品頁展示；{@code availableStock} 取自 Redis 即時餘量。 */
public record ActivityView(
        Long activityId,
        Long productId,
        String productName,
        BigDecimal seckillPrice,
        int totalStock,
        long availableStock,
        int perUserLimit,
        Instant startAt,
        Instant endAt,
        String status,
        boolean purchasable
) {

    public static ActivityView of(SeckillActivity activity, long availableStock, Instant now) {
        return new ActivityView(
                activity.id(),
                activity.productId(),
                activity.productName(),
                activity.seckillPrice(),
                activity.totalStock(),
                availableStock,
                activity.perUserLimit(),
                activity.period().startAt(),
                activity.period().endAt(),
                activity.status().name(),
                activity.isPurchasableAt(now) && availableStock > 0);
    }
}
