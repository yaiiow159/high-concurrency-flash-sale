package com.flashsale.infrastructure.adapter.out.persistence.mapper;

import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.OrderStatus;
import com.flashsale.domain.order.SeckillOrder;
import com.flashsale.infrastructure.adapter.out.persistence.entity.SeckillOrderEntity;

/** Entity ↔ 訂單聚合根轉換。 */
public final class OrderMapper {

    private OrderMapper() {
    }

    public static SeckillOrderEntity toEntity(SeckillOrder order) {
        return new SeckillOrderEntity(
                order.orderNo().value(),
                order.activityId(),
                order.userId(),
                order.requestId(),
                order.quantity(),
                order.amount(),
                order.status().name(),
                order.createdAt(),
                order.paidAt(),
                order.closeReason(),
                order.version());
    }

    public static SeckillOrder toDomain(SeckillOrderEntity entity) {
        return SeckillOrder.restore(
                OrderNo.of(entity.getOrderNo()),
                entity.getActivityId(),
                entity.getUserId(),
                entity.getRequestId(),
                entity.getQuantity(),
                entity.getAmount(),
                OrderStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getPaidAt(),
                entity.getCloseReason(),
                entity.getVersion());
    }
}
