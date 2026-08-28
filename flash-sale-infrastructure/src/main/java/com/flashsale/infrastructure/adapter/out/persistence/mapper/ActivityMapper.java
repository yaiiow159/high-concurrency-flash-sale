package com.flashsale.infrastructure.adapter.out.persistence.mapper;

import com.flashsale.domain.activity.ActivityStatus;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.infrastructure.adapter.out.persistence.entity.SeckillActivityEntity;

/** Entity ↔ 聚合根轉換。手寫而非用 MapStruct，因為只有兩處且轉換規則需要明確可讀。 */
public final class ActivityMapper {

    private ActivityMapper() {
    }

    public static SeckillActivity toDomain(SeckillActivityEntity entity) {
        return SeckillActivity.builder()
                .id(entity.getId())
                .productId(entity.getProductId())
                .productName(entity.getProductName())
                .seckillPrice(entity.getSeckillPrice())
                .totalStock(entity.getTotalStock())
                .perUserLimit(entity.getPerUserLimit())
                .period(entity.getStartAt(), entity.getEndAt())
                .status(ActivityStatus.valueOf(entity.getStatus()))
                .version(entity.getVersion())
                .build();
    }
}
