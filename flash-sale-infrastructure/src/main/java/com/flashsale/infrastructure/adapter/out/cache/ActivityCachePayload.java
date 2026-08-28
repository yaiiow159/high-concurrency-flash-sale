package com.flashsale.infrastructure.adapter.out.cache;

import com.flashsale.domain.activity.ActivityStatus;
import com.flashsale.domain.activity.SeckillActivity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 活動快取的序列化格式。
 *
 * <p><b>刻意不直接序列化聚合根</b>。理由有三：
 * <ul>
 *   <li>聚合根有私有建構子與不變條件，硬要 Jackson 反射建構會破壞封裝</li>
 *   <li>快取中可能存有舊版本的資料，直接反序列化到聚合根會讓重構寸步難行</li>
 *   <li>聚合根未來若加入不該外洩的欄位，會靜默地被寫進快取</li>
 * </ul>
 * 用一個獨立的扁平 record 當作快取契約，兩者的演進就此解耦。
 */
public record ActivityCachePayload(
        Long id,
        Long productId,
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
                activity.productId(),
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
                .productId(productId)
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
