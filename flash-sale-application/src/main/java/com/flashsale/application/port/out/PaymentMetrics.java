package com.flashsale.application.port.out;

import com.flashsale.application.port.out.PaymentMetrics;

/** 付款流程的業務指標。 */
public interface PaymentMetrics {

    void recordInitiated(String status);

    /**
     * 回調處理結果。
     *
     * @param result settled / duplicate / failed / refund-required / invalid-signature
     */
    void recordCallback(String result);

    void recordRefund(boolean succeeded);
}
