package com.flashsale.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 認證相關設定。
 *
 * @param jwt      令牌驗證設定
 * @param devToken 本機開發用的發證端點；<b>正式環境必須關閉</b>
 */
@ConfigurationProperties(prefix = "flash-sale.security")
public record SecurityProperties(
        @DefaultValue Jwt jwt,
        @DefaultValue DevToken devToken
) {

    /**
     * @param secret    HMAC 簽章金鑰。HS256 要求至少 256 bits（32 個 ASCII 字元），
     *                  長度不足時 Nimbus 會直接拒絕，不會默默降級成弱金鑰
     * @param issuer    簽發者，驗證時比對；不符即拒絕
     * @param audience  受眾，驗證時比對。<b>這一項最常被省略，但少了它，
     *                  同一個 IdP 簽給其他服務的令牌就能拿來存取本服務</b>
     * @param ttl       發證有效期（僅開發端點使用）
     */
    public record Jwt(
            @DefaultValue("dev-only-secret-change-me-in-production-0123456789abcdef") String secret,
            @DefaultValue("flash-sale") String issuer,
            @DefaultValue("flash-sale-api") String audience,
            @DefaultValue("2h") Duration ttl) {
    }

    /**
     * @param enabled 是否啟用 {@code POST /api/v1/auth/dev-token}。
     *                預設由環境變數控制，正式部署設為 {@code false} 即可整個 Bean 都不會註冊
     */
    public record DevToken(@DefaultValue("false") boolean enabled) {
    }
}
