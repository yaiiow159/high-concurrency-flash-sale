package com.flashsale.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** JWT 簽發與驗證設定。 */
@ConfigurationProperties(prefix = "flash-sale.security.jwt")
public record JwtProperties(
        @DefaultValue("dev-only-secret-change-me-in-production-0123456789abcdef") String secret,
        @DefaultValue("flash-sale") String issuer,
        @DefaultValue("flash-sale-api") String audience,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("7d") Duration refreshTokenTtl
) {
}
