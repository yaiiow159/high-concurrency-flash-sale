package com.flashsale.infrastructure.adapter.out.persistence.mapper;

import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderChannel;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.OrderStatus;
import com.flashsale.infrastructure.adapter.out.persistence.entity.OrderEntity;
import com.flashsale.infrastructure.adapter.out.persistence.entity.OrderLineEntity;

/** Entity ↔ 訂單聚合根轉換。 */
public final class OrderMapper {

    private OrderMapper() {
    }

    public static OrderEntity toEntity(Order order) {
        OrderEntity entity = new OrderEntity(
                order.orderNo().value(),
                order.userId(),
                order.channel().name(),
                order.requestId(),
                order.totalAmount(),
                order.status().name(),
                order.createdAt(),
                order.paidAt(),
                order.closeReason());

        order.lines().forEach(line -> entity.addLine(new OrderLineEntity(
                line.skuId(), line.skuSnapshot(), line.unitPrice(),
                line.quantity(), line.sourceActivityId())));
        return entity;
    }

    public static Order toDomain(OrderEntity entity) {
        return Order.restore(
                OrderNo.of(entity.getOrderNo()),
                entity.getUserId(),
                OrderChannel.valueOf(entity.getChannel()),
                entity.getRequestId(),
                entity.getLines().stream()
                        .map(line -> new OrderLine(line.getSkuId(), line.getSkuSnapshot(),
                                line.getUnitPrice(), line.getQuantity(), line.getSourceActivityId()))
                        .toList(),
                entity.getTotalAmount(),
                OrderStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getPaidAt(),
                entity.getCloseReason(),
                entity.getVersion());
    }
}
