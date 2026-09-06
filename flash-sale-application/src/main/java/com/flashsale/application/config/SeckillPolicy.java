package com.flashsale.application.config;

import java.time.Duration;

/** 秒殺流程的策略參數。 */
public record SeckillPolicy(
        Duration paymentWindow,
        Duration stockKeyTtlBuffer,
        int compensationBatchSize
) {

    public SeckillPolicy {
        if (paymentWindow == null || paymentWindow.isNegative() || paymentWindow.isZero()) {
            throw new IllegalArgumentException("paymentWindow 必須為正值");
        }
        if (stockKeyTtlBuffer == null || stockKeyTtlBuffer.isNegative()) {
            throw new IllegalArgumentException("stockKeyTtlBuffer 不可為負值");
        }
        if (compensationBatchSize <= 0) {
            throw new IllegalArgumentException("compensationBatchSize 必須大於 0");
        }
    }

    public static SeckillPolicy defaults() {
        return new SeckillPolicy(Duration.ofMinutes(15), Duration.ofHours(2), 200);
    }
}
