package com.flashsale.infrastructure.config;

import com.flashsale.application.config.QualificationSettings;
import com.flashsale.domain.risk.RiskPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 把設定翻成應用層與領域層認得的純資料物件。 */
@Configuration
@EnableConfigurationProperties(RiskProperties.class)
public class RiskConfig {

    @Bean
    public RiskPolicy riskPolicy(RiskProperties properties) {
        return new RiskPolicy(properties.youngAccountSeconds(), properties.maxUsersPerIp(),
                properties.maxUsersPerDevice(), properties.maxQualificationsPerUser(), properties.rejectScore());
    }

    @Bean
    public QualificationSettings qualificationSettings(RiskProperties properties) {
        return new QualificationSettings(properties.requireQualification(), properties.tokenTtl(),
                properties.leadTime(), properties.challengeTtl());
    }
}
