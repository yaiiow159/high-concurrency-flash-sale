package com.flashsale.infrastructure.adapter.out.cache;

import com.flashsale.domain.activity.ActivityStatus;
import com.flashsale.domain.activity.SeckillActivity;

import java.math.BigDecimal;
import java.time.Instant;

/** 活動快取的序列化格式。 */
public record ActivityCachePayload(
        Long id,
        Long skuId,
        String productName,
        BigDecimal seckillPrice,
        int totalStock,
        int perUserLimit,
        Instant startAt,
        Instant endAt,
        String status,
        long version
) {

    public static ActivityCachePayload from(SeckillActivity activity) {
        return new ActivityCachePayload(
                activity.id(),
                activity.skuId(),
                activity.productName(),
                activity.seckillPrice(),
                activity.totalStock(),
                activity.perUserLimit(),
                activity.period().startAt(),
                activity.period().endAt(),
                activity.status().name(),
                activity.version());
    }

    public SeckillActivity toDomain() {
        return SeckillActivity.builder()
                .id(id)
                .skuId(skuId)
                .productName(productName)
                .seckillPrice(seckillPrice)
                .totalStock(totalStock)
                .perUserLimit(perUserLimit)
                .period(startAt, endAt)
                .status(ActivityStatus.valueOf(status))
                .version(version)
                .build();
    }
}
