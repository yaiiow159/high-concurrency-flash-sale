package com.flashsale.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** 風控與資格預檢。secret 正式環境務必以環境變數覆寫，否則任何人都能自己簽資格。 */
@ConfigurationProperties(prefix = "flash-sale.risk")
public record RiskProperties(
        @DefaultValue("dev-only-risk-secret-change-me-0123456789abcdef") String secret,
        @DefaultValue("true") boolean requireQualification,
        @DefaultValue("15m") Duration tokenTtl,
        @DefaultValue("30m") Duration leadTime,
        @DefaultValue("3m") Duration challengeTtl,
        @DefaultValue("10m") Duration signalWindow,
        @DefaultValue("600") long youngAccountSeconds,
        @DefaultValue("5") int maxUsersPerIp,
        @DefaultValue("3") int maxUsersPerDevice,
        @DefaultValue("5") int maxQualificationsPerUser,
        @DefaultValue("60") int rejectScore
) {
}
