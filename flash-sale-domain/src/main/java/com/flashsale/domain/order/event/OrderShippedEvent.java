package com.flashsale.domain.order.event;

import com.flashsale.domain.order.Order;
import com.flashsale.domain.shared.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** 訂單出貨事件。 */
public record OrderShippedEvent(
        String eventId,
        int schemaVersion,
        String orderNo,
        Long userId,
        Instant occurredAt
) implements DomainEvent {

    public static final String TYPE = "order.shipped";
    public static final int SCHEMA_VERSION = 1;

    public static OrderShippedEvent of(Order order, Instant shippedAt) {
        return new OrderShippedEvent(UUID.randomUUID().toString(), SCHEMA_VERSION,
                order.orderNo().value(), order.userId(), shippedAt);
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
