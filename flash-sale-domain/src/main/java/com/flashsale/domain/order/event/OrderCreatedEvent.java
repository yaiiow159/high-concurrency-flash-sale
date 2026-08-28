package com.flashsale.domain.order.event;

import com.flashsale.domain.order.SeckillOrder;
import com.flashsale.domain.shared.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 訂單建立完成事件，供下游（通知、資料分析、履約）消費。 */
public record OrderCreatedEvent(
        String eventId,
        String orderNo,
        Long activityId,
        Long userId,
        int quantity,
        BigDecimal amount,
        Instant occurredAt
) implements DomainEvent {

    public static final String TYPE = "order.created";

    public static OrderCreatedEvent of(SeckillOrder order, Instant now) {
        return new OrderCreatedEvent(
                UUID.randomUUID().toString(),
                order.orderNo().value(),
                order.activityId(),
                order.userId(),
                order.quantity(),
                order.amount(),
                now);
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
