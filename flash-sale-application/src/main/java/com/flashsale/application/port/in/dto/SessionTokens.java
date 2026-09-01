package com.flashsale.application.port.in.dto;

import java.time.Duration;

/**
 * 一次登入或續期產生的令牌組。
 *
 * <p>{@code refreshToken} 是<b>唯一一次</b>會出現原值的地方——
 * 儲存區只留雜湊，之後再也取不回來。用戶端沒收好就只能重新登入。
 */
public record SessionTokens(
        String accessToken,
        String refreshToken,
        String tokenType,
        long accessTokenExpiresInSeconds,
        long refreshTokenExpiresInSeconds
) {

    public static SessionTokens of(String accessToken, Duration accessTtl,
                                   String refreshToken, Duration refreshTtl) {
        return new SessionTokens(accessToken, refreshToken, "Bearer",
                accessTtl.toSeconds(), refreshTtl.toSeconds());
    }
}
