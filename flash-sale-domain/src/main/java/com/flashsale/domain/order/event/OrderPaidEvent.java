package com.flashsale.domain.order.event;

import com.flashsale.domain.order.SeckillOrder;
import com.flashsale.domain.shared.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 訂單付款完成事件，下游據此進入履約流程並把預扣庫存轉為實際銷量。 */
public record OrderPaidEvent(
        String eventId,
        String orderNo,
        Long activityId,
        Long userId,
        BigDecimal amount,
        Instant occurredAt
) implements DomainEvent {

    public static final String TYPE = "order.paid";

    public static OrderPaidEvent of(SeckillOrder order, Instant paidAt) {
        return new OrderPaidEvent(
                UUID.randomUUID().toString(),
                order.orderNo().value(),
                order.activityId(),
                order.userId(),
                order.amount(),
                paidAt);
    }

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public String aggregateId() {
        return orderNo;
    }
}
