package com.flashsale.application.port.out;

import com.flashsale.domain.payment.Payment;

import java.math.BigDecimal;
import java.util.Map;

/** 金流閘道埠（出站）。 */
public interface PaymentGateway {

    /**
     * 發起付款。
     *
     * @return 付款意圖，含導向使用者的付款頁網址
     */
    PaymentIntent initiate(Payment payment);

    /** 驗證回調的簽章。 */
    boolean verifyCallbackSignature(Map<String, String> parameters);

    /** 退款。 */
    RefundOutcome refund(Payment payment, BigDecimal amount, String idempotencyKey);

    /** 發起付款的結果。 */
    record PaymentIntent(String gatewayReference, String paymentUrl) {
    }

    /** 退款結果。 */
    record RefundOutcome(boolean succeeded, String gatewayReference, String failureReason) {

        public static RefundOutcome success(String gatewayReference) {
            return new RefundOutcome(true, gatewayReference, null);
        }

        public static RefundOutcome failure(String reason) {
            return new RefundOutcome(false, null, reason);
        }
    }
}
