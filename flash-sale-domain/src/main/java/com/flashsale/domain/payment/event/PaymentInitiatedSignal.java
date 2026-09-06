package com.flashsale.domain.payment.event;

/** 付款已發起的行程內訊號。 */
public record PaymentInitiatedSignal(String paymentNo, String orderNo) {
}
