package com.flashsale.domain.aftersales.event;

import com.flashsale.domain.aftersales.ReturnRequest;
import com.flashsale.domain.shared.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 退款已核可，請執行。 */
public record RefundRequestedEvent(
        String eventId,
        String returnNo,
        String orderNo,
        Long userId,
        BigDecimal refundAmount,
        List<RestockLine> restockLines,
        Instant occurredAt
) implements DomainEvent {

    public static final String TYPE = "refund.requested";

    /** 要回補到<b>一般庫存</b>的品項。絕不回秒殺池（ADR-0011 決策 4）。 */
    public record RestockLine(Long skuId, int quantity) {
    }

    public static RefundRequestedEvent of(ReturnRequest request, Instant now) {
        return new RefundRequestedEvent(
                UUID.randomUUID().toString(),
                request.returnNo().value(),
                request.orderNo().value(),
                request.userId(),
                request.refundAmount(),
                request.restockableLines().stream()
                        .map(line -> new RestockLine(line.skuId(), line.quantity()))
                        .toList(),
                now);
    }

    @Override
    public String eventType() {
        return TYPE;
    }

    /** 以訂單號作為 partition key，而非退貨單號。 */
    @Override
    public String aggregateId() {
        return orderNo;
    }
}
