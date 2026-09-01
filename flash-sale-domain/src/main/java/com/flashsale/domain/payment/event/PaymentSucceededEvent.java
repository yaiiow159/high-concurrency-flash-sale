package com.flashsale.domain.payment.event;

import com.flashsale.domain.payment.Payment;
import com.flashsale.domain.shared.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 收款成功事件，供發票、通知、資料分析等下游消費。 */
public record PaymentSucceededEvent(
        String eventId,
        String paymentNo,
        String orderNo,
        Long userId,
        BigDecimal amount,
        String gatewayTransactionId,
        Instant occurredAt
) implements DomainEvent {

    public static final String TYPE = "payment.succeeded";

    public static PaymentSucceededEvent of(Payment payment, Instant paidAt) {
        return new PaymentSucceededEvent(
                UUID.randomUUID().toString(),
                payment.paymentNo().value(),
                payment.orderNo().value(),
                payment.userId(),
                payment.amount(),
                payment.gatewayTransactionId(),
                paidAt);
    }

    @Override
    public String eventType() {
        return TYPE;
    }

    /** 以訂單號為分區鍵，讓同一訂單的付款與訂單事件落在同一分區，保持相對順序。 */
    @Override
    public String aggregateId() {
        return orderNo;
    }
}
