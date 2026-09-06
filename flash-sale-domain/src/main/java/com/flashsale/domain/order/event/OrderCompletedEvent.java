package com.flashsale.domain.order.event;

import com.flashsale.domain.order.Order;
import com.flashsale.domain.shared.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** 訂單完成（已送達）事件。 */
public record OrderCompletedEvent(
        String eventId,
        int schemaVersion,
        String orderNo,
        Long userId,
        Instant occurredAt
) implements DomainEvent {

    public static final String TYPE = "order.completed";
    public static final int SCHEMA_VERSION = 1;

    public static OrderCompletedEvent of(Order order, Instant completedAt) {
        return new OrderCompletedEvent(UUID.randomUUID().toString(), SCHEMA_VERSION,
                order.orderNo().value(), order.userId(), completedAt);
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
