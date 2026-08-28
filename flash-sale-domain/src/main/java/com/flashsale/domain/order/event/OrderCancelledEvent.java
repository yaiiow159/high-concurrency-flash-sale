package com.flashsale.domain.order.event;

import com.flashsale.domain.order.SeckillOrder;
import com.flashsale.domain.shared.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * 訂單關閉事件（取消或失敗）。
 *
 * <p>此事件是 Saga 補償鏈的觸發點：消費端據此把預扣的 Redis 庫存退回。
 * 攜帶 {@code requestId} 是為了讓補償腳本能做冪等判斷——重複消費不會把庫存退兩次。
 */
public record OrderCancelledEvent(
        String eventId,
        String orderNo,
        Long activityId,
        Long userId,
        String requestId,
        int quantity,
        String reason,
        Instant occurredAt
) implements DomainEvent {

    public static final String TYPE = "order.cancelled";

    public static OrderCancelledEvent of(SeckillOrder order, String reason, Instant now) {
        return new OrderCancelledEvent(
                UUID.randomUUID().toString(),
                order.orderNo().value(),
                order.activityId(),
                order.userId(),
                order.requestId(),
                order.quantity(),
                reason,
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
