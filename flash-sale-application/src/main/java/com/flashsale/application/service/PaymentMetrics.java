package com.flashsale.application.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 付款流程的業務指標。 */
@Component
public class PaymentMetrics {

    private static final String INITIATED_COUNTER = "payment.initiated.total";
    private static final String CALLBACK_COUNTER = "payment.callback.total";
    private static final String REFUND_COUNTER = "payment.refund.total";

    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordInitiated(String status) {
        Counter.builder(INITIATED_COUNTER)
                .tag("status", status)
                .description("發起付款次數")
                .register(registry)
                .increment();
    }

    /**
     * 回調處理結果。
     *
     * @param result settled / duplicate / failed / refund-required / invalid-signature
     */
    public void recordCallback(String result) {
        Counter.builder(CALLBACK_COUNTER)
                .tag("result", result)
                .description("金流回調處理結果；refund-required 與 invalid-signature 應恆為 0")
                .register(registry)
                .increment();
    }

    public void recordRefund(boolean succeeded) {
        Counter.builder(REFUND_COUNTER)
                .tag("result", succeeded ? "success" : "failure")
                .description("退款執行結果")
                .register(registry)
                .increment();
    }
}
