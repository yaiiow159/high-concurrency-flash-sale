package com.flashsale.application.port.in.dto;

import com.flashsale.domain.order.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 訂單查詢結果。
 *
 * <p>{@code processing=true} 代表庫存已扣、訂單尚在 MQ 佇列中（DB 還查不到），
 * 前端應繼續輪詢而非顯示「訂單不存在」。
 */
public record OrderView(
        String orderNo,
        Long userId,
        String channel,
        List<Line> lines,
        BigDecimal totalAmount,
        String status,
        String closeReason,
        Instant createdAt,
        Instant paidAt,
        boolean processing
) {

    /** 訂單行。刻意不含 sourceActivityId——那是內部追溯用的，前端不需要。 */
    public record Line(Long skuId, String skuSnapshot, BigDecimal unitPrice,
                       int quantity, BigDecimal subtotal) {
    }

    public static OrderView from(Order order) {
        return new OrderView(
                order.orderNo().value(),
                order.userId(),
                order.channel().name(),
                order.lines().stream()
                        .map(line -> new Line(line.skuId(), line.skuSnapshot(),
                                line.unitPrice(), line.quantity(), line.subtotal()))
                        .toList(),
                order.totalAmount(),
                order.status().name(),
                order.closeReason(),
                order.createdAt(),
                order.paidAt(),
                false);
    }

    /** 庫存已扣減、訂單仍在非同步建立中。 */
    public static OrderView processing(String orderNo) {
        return new OrderView(orderNo, null, null, List.of(), null,
                "PROCESSING", null, null, null, true);
    }
}
