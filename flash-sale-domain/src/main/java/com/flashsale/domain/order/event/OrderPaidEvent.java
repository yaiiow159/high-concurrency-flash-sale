package com.flashsale.domain.order.event;

import com.flashsale.domain.order.Order;
import com.flashsale.domain.shared.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 訂單付款完成事件，下游據此進入履約流程並把預扣庫存轉為實際銷量。 */
public record OrderPaidEvent(
        String eventId,
        int schemaVersion,
        String orderNo,
        Long userId,
        BigDecimal totalAmount,
        Instant occurredAt
) implements DomainEvent {

    public static final String TYPE = "order.paid";
    public static final int SCHEMA_VERSION = 2;

    public static OrderPaidEvent of(Order order, Instant paidAt) {
        return new OrderPaidEvent(
                UUID.randomUUID().toString(),
                SCHEMA_VERSION,
                order.orderNo().value(),
                order.userId(),
                order.totalAmount(),
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
