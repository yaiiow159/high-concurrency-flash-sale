package com.flashsale.application.port.in.dto;

import java.util.List;

/**
 * 這張訂單現在能評什麼。
 *
 * <p>由後端算而不是讓前端比對訂單行與既有評價——前端再實作一次的話，
 * 症狀會是「畫面說可以評，送出卻被拒絕」。
 * 與可退數量交給後端算（{@code ReturnableView}）是同一個理由。
 *
 * @param reason 不能評價時的原因，直接顯示給使用者；可評價時為 {@code null}
 */
public record ReviewableView(
        String orderNo,
        boolean reviewable,
        String reason,
        List<Line> lines
) {

    /** @param pending 這一項還沒評價過。為 {@code false} 時畫面應標成「已評價」而不是隱藏 */
    public record Line(Long skuId, String skuSnapshot, boolean pending) {
    }

    public static ReviewableView notYet(String orderNo, String reason) {
        return new ReviewableView(orderNo, false, reason, List.of());
    }

    public static ReviewableView of(String orderNo, List<Line> lines) {
        // 每一項都評過了就等於整張沒東西可評。回 true 卻沒有任何可選項目，
        // 使用者會看到一張空表單而不知道為什麼
        boolean anyPending = lines.stream().anyMatch(Line::pending);
        return new ReviewableView(orderNo, anyPending,
                anyPending ? null : "這張訂單的商品都已經評價過了", lines);
    }
}
