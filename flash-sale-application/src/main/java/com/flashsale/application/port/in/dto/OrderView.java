package com.flashsale.application.port.in.dto;

import com.flashsale.domain.order.SeckillOrder;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 訂單查詢結果。
 *
 * <p>{@code processing=true} 代表庫存已扣、訂單尚在 MQ 佇列中（DB 還查不到），
 * 前端應繼續輪詢而非顯示「訂單不存在」。
 */
public record OrderView(
        String orderNo,
        Long activityId,
        Long userId,
        Integer quantity,
        BigDecimal amount,
        String status,
        String closeReason,
        Instant createdAt,
        boolean processing
) {

    public static OrderView from(SeckillOrder order) {
        return new OrderView(
                order.orderNo().value(),
                order.activityId(),
                order.userId(),
                order.quantity(),
                order.amount(),
                order.status().name(),
                order.closeReason(),
                order.createdAt(),
                false);
    }

    /** 庫存已扣減、訂單仍在非同步建立中。 */
    public static OrderView processing(String orderNo) {
        return new OrderView(orderNo, null, null, null, null, "PROCESSING", null, null, true);
    }
}
