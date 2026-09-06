package com.flashsale.application.port.in.dto;

import com.flashsale.domain.membership.PointTransaction;

import java.time.Instant;

/** 一筆積分流水。 */
public record PointTransactionView(
        Long id,
        long delta,
        long balanceAfter,
        String reason,
        String reasonName,
        String refNo,
        Instant createdAt
) {

    public static PointTransactionView from(PointTransaction transaction) {
        return new PointTransactionView(
                transaction.id(),
                transaction.delta(),
                transaction.balanceAfter(),
                transaction.reason().name(),
                transaction.reason().displayName(),
                transaction.refNo(),
                transaction.createdAt());
    }
}
