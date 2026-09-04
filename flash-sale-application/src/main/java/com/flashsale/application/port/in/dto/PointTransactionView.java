package com.flashsale.application.port.in.dto;

import com.flashsale.domain.membership.PointTransaction;

import java.time.Instant;

/**
 * 一筆積分流水。
 *
 * <p>帶上 {@code balanceAfter}：使用者看流水時想確認的是
 * 「這一筆之後我還剩多少」，而讓他自己從最新餘額往回加是不合理的要求。
 *
 * @param reason     代號，供前端對應圖示
 * @param reasonName 已經翻好的中文。翻譯放後端而不是前端——
 *                   同一組代號會出現在後台與客服工具上，翻兩次遲早會不一致
 */
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
