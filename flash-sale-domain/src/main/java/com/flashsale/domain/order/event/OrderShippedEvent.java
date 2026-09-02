package com.flashsale.domain.order.event;

import com.flashsale.domain.order.Order;
import com.flashsale.domain.shared.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * 訂單出貨事件。
 *
 * <p>下游用途是通知（寄出貨信）與資料分析，<b>不涉及庫存</b>——
 * 庫存在付款時就已經是實際銷量，出貨只是把貨從倉庫搬走。
 * 若哪天有人在這個事件的消費端寫了扣庫存，那就是扣了第二次。
 */
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
