package com.flashsale.application.config;

import java.time.Duration;

/**
 * 秒殺流程的策略參數。
 *
 * <p>刻意做成純 record 而非 {@code @ConfigurationProperties}：
 * 應用層不該認得 Spring Boot 的設定綁定機制，也不該為了跑一個單元測試
 * 就必須拉起一個 Spring 環境。由基礎設施層綁定 yml 後注入此不可變物件。
 *
 * @param paymentWindow        付款期限，逾時由補償排程關單並退庫
 * @param stockKeyTtlBuffer    Redis 庫存鍵在活動結束後的保留時長，
 *                             用來容納「活動剛結束但補償還沒跑完」的尾巴
 * @param compensationBatchSize 補償排程單批處理的訂單上限
 */
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
