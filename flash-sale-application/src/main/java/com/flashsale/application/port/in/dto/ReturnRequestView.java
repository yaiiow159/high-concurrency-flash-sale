package com.flashsale.application.port.in.dto;

import com.flashsale.domain.aftersales.ReturnRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 退貨單的對外表述。
 *
 * <p>{@code refundAmount} 由退貨行推導，與領域模型同一套算法——
 * 前端不自己乘一次。畫面上的金額與實際退款金額必須是同一個數字，
 * 而讓兩邊各算一次，遲早會因為四捨五入或幣別而分岔。
 */
public record ReturnRequestView(
        String returnNo,
        String orderNo,
        String status,
        String reason,
        String reasonDetail,
        boolean requiresGoodsReturn,
        BigDecimal refundAmount,
        List<Line> lines,
        String reviewNote,
        Instant createdAt,
        Instant reviewedAt,
        Instant receivedAt,
        Instant refundedAt
) {

    public record Line(
            Long skuId,
            String skuSnapshot,
            BigDecimal unitPrice,
            int quantity,
            Boolean restockable
    ) {
    }

    public static ReturnRequestView from(ReturnRequest request) {
        return new ReturnRequestView(
                request.returnNo().value(),
                request.orderNo().value(),
                request.status().name(),
                request.reason().name(),
                request.reasonDetail(),
                request.requiresGoodsReturn(),
                request.refundAmount(),
                request.lines().stream()
                        .map(line -> new Line(line.skuId(), line.skuSnapshot(),
                                line.unitPrice(), line.quantity(), line.restockable()))
                        .toList(),
                request.reviewNote(),
                request.createdAt(),
                request.reviewedAt(),
                request.receivedAt(),
                request.refundedAt());
    }
}
