package com.flashsale.domain.payment.event;

import com.flashsale.domain.payment.Payment;
import com.flashsale.domain.shared.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 收款成功但無法入帳，需要退款。 */
public record PaymentRefundRequiredEvent(
        String eventId,
        String paymentNo,
        String orderNo,
        Long userId,
        BigDecimal amount,
        String gatewayTransactionId,
        String reason,
        Instant occurredAt
) implements DomainEvent {

    public static final String TYPE = "payment.refund-required";

    public static PaymentRefundRequiredEvent of(Payment payment, String reason, Instant now) {
        return new PaymentRefundRequiredEvent(
                UUID.randomUUID().toString(),
                payment.paymentNo().value(),
                payment.orderNo().value(),
                payment.userId(),
                payment.amount(),
                payment.gatewayTransactionId(),
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
