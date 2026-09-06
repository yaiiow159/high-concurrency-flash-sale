package com.flashsale.application.port.in.dto;

import java.time.Duration;

/** 一次登入或續期產生的令牌組。 */
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
