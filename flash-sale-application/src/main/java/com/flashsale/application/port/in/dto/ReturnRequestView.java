package com.flashsale.application.port.in.dto;

import com.flashsale.domain.aftersales.ReturnRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 退貨單的對外表述。 */
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
        /** 退款發起時間。有值而 refundedAt 沒有，代表錢還在路上 */
        Instant refundStartedAt,
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
                request.refundStartedAt(),
                request.refundedAt());
    }
}
