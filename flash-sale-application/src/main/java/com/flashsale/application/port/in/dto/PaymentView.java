package com.flashsale.application.port.in.dto;

import com.flashsale.domain.payment.Payment;

import java.math.BigDecimal;
import java.time.Instant;

/** 付款單詳情。刻意不含閘道交易編號——那是對帳用的內部識別，沒有必要外洩。 */
public record PaymentView(
        String paymentNo,
        String orderNo,
        BigDecimal amount,
        String status,
        Instant createdAt,
        Instant paidAt,
        String failureReason
) {

    public static PaymentView from(Payment payment) {
        return new PaymentView(
                payment.paymentNo().value(),
                payment.orderNo().value(),
                payment.amount(),
                payment.status().name(),
                payment.createdAt(),
                payment.paidAt(),
                payment.failureReason());
    }
}
