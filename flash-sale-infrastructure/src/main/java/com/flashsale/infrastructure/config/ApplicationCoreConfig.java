package com.flashsale.infrastructure.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.flashsale.application.config.AuthPolicy;
import com.flashsale.application.config.ReconciliationPolicy;
import com.flashsale.application.config.SeckillPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/**
 * 應用層所需的基礎 Bean。
 *
 * <p>這裡是「Spring Boot 的設定機制」與「不認得 Spring Boot 的應用層」之間的轉接點：
 * 把 {@code @ConfigurationProperties} 綁定的結果，轉成應用層自己定義的純值物件。
 */
@Configuration
@EnableConfigurationProperties({FlashSaleProperties.class, JwtProperties.class, PaymentProperties.class,
        AdmissionProperties.class})
public class ApplicationCoreConfig {

    /**
     * 系統時鐘。
     *
     * <p>所有需要「現在幾點」的程式碼都注入這個 Bean，而非呼叫 {@code Instant.now()}。
     * 差別在於測試：要驗證「活動結束後不能下單」，只需注入一個固定時鐘，
     * 而不是把測試環境的系統時間改掉，或讓測試 sleep 等到活動結束。
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    /** 把設定綁定結果轉為應用層的策略值物件。 */
    @Bean
    public SeckillPolicy seckillPolicy(FlashSaleProperties properties) {
        return new SeckillPolicy(
                properties.order().paymentWindow(),
                properties.stock().keyTtlBuffer(),
                properties.order().compensationBatchSize());
    }

    /**
     * 把 JWT 設定轉為應用層的認證策略值物件。
     *
     * <p>應用層只關心「令牌各活多久」，不需要知道簽章金鑰或演算法。
     */
    @Bean
    public AuthPolicy authPolicy(JwtProperties jwtProperties) {
        return new AuthPolicy(jwtProperties.accessTokenTtl(), jwtProperties.refreshTokenTtl());
    }

    /** 把設定綁定結果轉為應用層的對帳策略值物件。 */
    @Bean
    public ReconciliationPolicy reconciliationPolicy(FlashSaleProperties properties) {
        return new ReconciliationPolicy(
                properties.reconciliation().orphanGracePeriod(),
                properties.reconciliation().scanBatchSize(),
                properties.reconciliation().autoRepairOrphans());
    }

    /**
     * 給快取與訊息使用的 ObjectMapper。
     *
     * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES=false} 是跨版本相容的關鍵：
     * 新版本的生產端加了欄位後，尚未升級的消費端才不會因為看到不認識的欄位就整批失敗。
     * 這讓滾動升級成為可能。
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }
}
