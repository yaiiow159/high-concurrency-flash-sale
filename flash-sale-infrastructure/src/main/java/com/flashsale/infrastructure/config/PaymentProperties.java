package com.flashsale.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 金流設定。
 *
 * @param callbackSecret        回調簽章金鑰。<b>正式環境由金流商提供</b>，
 *                              絕不可用預設值——那等於公開了偽造付款成功的方法
 * @param simulatedCheckoutUrl  模擬付款頁網址
 * @param simulateCallbackDelay 模擬閘道多久後送出回調。真實金流是使用者操作完才回調，
 *                              這裡用一個短延遲模擬「非同步」這個本質特性
 * @param autoSucceed           模擬付款是否自動成功。設為 false 可測試失敗路徑
 */
@ConfigurationProperties(prefix = "flash-sale.payment")
public record PaymentProperties(
        @DefaultValue("dev-only-payment-secret-change-me-0123456789abcdef") String callbackSecret,
        @DefaultValue("http://localhost:8080/simulated-checkout") String simulatedCheckoutUrl,
        @DefaultValue("2s") Duration simulateCallbackDelay,
        @DefaultValue("true") boolean autoSucceed
) {
}
