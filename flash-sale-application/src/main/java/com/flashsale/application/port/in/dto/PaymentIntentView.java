package com.flashsale.application.port.in.dto;

/** 發起付款的結果，回傳給前端。 */
public record PaymentIntentView(
        String paymentNo,
        String orderNo,
        String paymentUrl,
        String status
) {
}
