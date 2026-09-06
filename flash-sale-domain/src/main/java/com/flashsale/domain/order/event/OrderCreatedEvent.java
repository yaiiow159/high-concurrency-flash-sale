package com.flashsale.domain.order.event;

import com.flashsale.domain.order.Order;
import com.flashsale.domain.shared.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 訂單建立完成事件，供下游（通知、資料分析、履約）消費。 */
public record OrderCreatedEvent(
        String eventId,
        int schemaVersion,
        String orderNo,
        Long userId,
        String channel,
        BigDecimal totalAmount,
        List<Line> lines,
        Instant occurredAt
) implements DomainEvent {

    public static final String TYPE = "order.created";
    /** v1 = 單品項（activityId/quantity/amount 平鋪）；v2 = 多品項。 */
    public static final int SCHEMA_VERSION = 2;

    /** 訂單行的事件表述。刻意扁平，事件是跨行程契約，不該直接序列化聚合根。 */
    public record Line(Long skuId, String skuSnapshot, BigDecimal unitPrice,
                       int quantity, Long sourceActivityId) {
    }

    public static OrderCreatedEvent of(Order order, Instant now) {
        return new OrderCreatedEvent(
                UUID.randomUUID().toString(),
                SCHEMA_VERSION,
                order.orderNo().value(),
                order.userId(),
                order.channel().name(),
                order.totalAmount(),
                order.lines().stream()
                        .map(line -> new Line(line.skuId(), line.skuSnapshot(),
                                line.unitPrice(), line.quantity(), line.sourceActivityId()))
                        .toList(),
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
