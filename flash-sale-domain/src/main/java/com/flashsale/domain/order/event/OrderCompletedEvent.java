package com.flashsale.domain.order.event;

import com.flashsale.domain.order.Order;
import com.flashsale.domain.shared.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * 訂單完成（已送達）事件。
 *
 * <p>這是鑑賞期與退貨期限的<b>起算點</b>，因此 {@code occurredAt}
 * 之後會被當成業務時間而不只是紀錄時間——退貨期限從這一刻算起。
 * 補送或重放這個事件會連帶影響退貨權利，消費端必須冪等。
 */
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
