package com.flashsale.domain.order.event;

import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.shared.DomainEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 訂單關閉事件（取消或失敗）。
 *
 * <p>此事件是 Saga 補償鏈的觸發點：消費端據此把預扣的 Redis 庫存退回。
 *
 * <p>攜帶 {@code requestId} 是為了讓補償腳本能做冪等判斷——重複消費不會把庫存退兩次。
 *
 * <p>多品項後改為攜帶 {@code restorations} 清單：一張訂單可能佔用多個活動的庫存，
 * 每一筆都要各自退回。單品項時代那個扁平的 {@code activityId + quantity}
 * 在多品項下會漏退。
 */
public record OrderCancelledEvent(
        String eventId,
        int schemaVersion,
        String orderNo,
        Long userId,
        String requestId,
        List<StockRestoration> restorations,
        String reason,
        Instant occurredAt
) implements DomainEvent {

    public static final String TYPE = "order.cancelled";
    public static final int SCHEMA_VERSION = 2;

    /** 需要退回的一筆秒殺庫存。一般下單的行不在此列——它們走資料庫庫存。 */
    public record StockRestoration(Long activityId, int quantity) {
    }

    public static OrderCancelledEvent of(Order order, String reason, Instant now) {
        List<StockRestoration> restorations = order.lines().stream()
                // 只有來自秒殺活動的行需要退回 Redis 庫存
                .filter(line -> line.sourceActivityId() != null)
                .map(line -> new StockRestoration(line.sourceActivityId(), line.quantity()))
                .toList();

        return new OrderCancelledEvent(
                UUID.randomUUID().toString(),
                SCHEMA_VERSION,
                order.orderNo().value(),
                order.userId(),
                order.requestId(),
                restorations,
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

    /** 為了兼容仍以「單一活動」思考的呼叫端而提供；多活動時只取第一筆會漏退。 */
    public boolean hasStockToRestore() {
        return !restorations.isEmpty();
    }
}
