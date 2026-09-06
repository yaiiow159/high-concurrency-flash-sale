package com.flashsale.infrastructure.adapter.out.persistence.mapper;

import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderChannel;
import com.flashsale.domain.order.OrderDiscount;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.shipping.ShippingMethod;
import com.flashsale.domain.order.OrderStatus;
import com.flashsale.domain.order.ShippingInfo;
import com.flashsale.infrastructure.adapter.out.persistence.entity.OrderEntity;
import com.flashsale.infrastructure.adapter.out.persistence.entity.OrderDiscountEntity;
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
                order.closeReason(),
                order.shippingFee(),
                order.shippingMethod().name(),
                order.buyerNote());

        ShippingInfo shipping = order.shippingInfo();
        if (shipping != null) {
            entity.applyShippingInfo(shipping.recipientName(), shipping.phone(),
                    shipping.postalCode(), shipping.region(),
                    shipping.district(), shipping.streetAddress());
        }

        order.discounts().forEach(discount -> entity.addDiscount(new OrderDiscountEntity(
                discount.sourceType(), discount.sourceId(),
                discount.name(), discount.amount())));

        order.lines().forEach(line -> entity.addLine(new OrderLineEntity(
                line.skuId(), line.skuSnapshot(), line.unitPrice(),
                line.quantity(), line.sourceActivityId(), line.allocatedAmount())));
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
                                line.getUnitPrice(), line.getQuantity(), line.getSourceActivityId(),
                                line.getAllocatedAmount()))
                        .toList(),
                entity.getTotalAmount(),
                toShippingInfo(entity),
                OrderStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getPaidAt(),
                entity.getCloseReason(),
                entity.getDiscounts().stream()
                        .map(discount -> new OrderDiscount(discount.getSourceType(),
                                discount.getSourceId(), discount.getName(), discount.getAmount()))
                        .toList(),
                entity.getVersion(),
                entity.getShippingFee(),
                ShippingMethod.valueOf(entity.getShippingMethod()),
                entity.buyerNote());
    }

    /** 秒殺訂單與 V8 之前建立的訂單都沒有收貨資訊。 */
    private static ShippingInfo toShippingInfo(OrderEntity entity) {
        if (entity.getShipRecipient() == null) {
            return null;
        }
        return new ShippingInfo(entity.getShipRecipient(), entity.getShipPhone(),
                entity.getShipPostalCode(), entity.getShipRegion(),
                entity.getShipDistrict(), entity.getShipStreet());
    }
}
