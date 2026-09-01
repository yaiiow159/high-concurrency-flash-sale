package com.flashsale.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * JWT 簽發與驗證設定。
 *
 * <p><b>放在基礎設施層而非 api 層</b>，因為簽發（出站）與驗證（入站）都需要它，
 * 而 api 依賴 infrastructure、反之不成立。
 *
 * @param secret          HMAC 金鑰。HS256 要求至少 256 bits（32 個 ASCII 字元）
 * @param issuer          簽發者，驗證時比對
 * @param audience        受眾，驗證時比對。少了它，同一 IdP 簽給其他服務的令牌也能存取本服務
 * @param accessTokenTtl  Access token 有效期，刻意設短——它無法撤銷
 * @param refreshTokenTtl Refresh token 有效期
 */
@ConfigurationProperties(prefix = "flash-sale.security.jwt")
public record JwtProperties(
        @DefaultValue("dev-only-secret-change-me-in-production-0123456789abcdef") String secret,
        @DefaultValue("flash-sale") String issuer,
        @DefaultValue("flash-sale-api") String audience,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("7d") Duration refreshTokenTtl
) {
}
