package com.flashsale.infrastructure.config;

import com.flashsale.application.service.MediaReconciliationService;
import com.flashsale.application.service.ProductMediaService;
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

/** 應用層所需的基礎 Bean。 */
@Configuration
@EnableConfigurationProperties({FlashSaleProperties.class, JwtProperties.class, PaymentProperties.class,
        AdmissionProperties.class,
        MediaProperties.class, BootstrapAdminProperties.class})
public class ApplicationCoreConfig {

    /** 系統時鐘。 */
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

    /** 把 JWT 設定轉為應用層的認證策略值物件。 */
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

    /** 給快取與訊息使用的 ObjectMapper。 */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }
    /** 預簽名 URL 的有效期。 */
    @Bean
    public ProductMediaService.MediaUploadTtl mediaUploadTtl(MediaProperties properties) {
        return new ProductMediaService.MediaUploadTtl(
                java.time.Duration.ofSeconds(properties.uploadTtlSeconds()));
    }

    /** 孤兒物件的寬限期。必須明顯長於任何進行中的上傳流程。 */
    @Bean
    public MediaReconciliationService.OrphanGrace mediaOrphanGrace(MediaProperties properties) {
        return new MediaReconciliationService.OrphanGrace(
                java.time.Duration.ofHours(properties.orphanGraceHours()));
    }

}
