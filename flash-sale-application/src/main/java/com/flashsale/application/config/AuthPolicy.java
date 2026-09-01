package com.flashsale.application.config;

import java.time.Duration;

/**
 * 認證流程的策略參數。
 *
 * @param accessTokenTtl  Access token 有效期。<b>刻意設短</b>——它無法被撤銷，
 *                        停權或登出後仍會在這段時間內有效，這是無狀態設計的固有空窗
 * @param refreshTokenTtl Refresh token 有效期，決定「多久沒用就要重新登入」
 */
public record AuthPolicy(Duration accessTokenTtl, Duration refreshTokenTtl) {

    public AuthPolicy {
        if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            throw new IllegalArgumentException("accessTokenTtl 必須為正值");
        }
        if (refreshTokenTtl == null || !refreshTokenTtl.minus(accessTokenTtl).isPositive()) {
            // refresh 比 access 短是設定錯誤：使用者會在還能用 access token 時就被迫重登。
            throw new IllegalArgumentException("refreshTokenTtl 必須明顯長於 accessTokenTtl");
        }
    }

    public static AuthPolicy defaults() {
        return new AuthPolicy(Duration.ofMinutes(15), Duration.ofDays(7));
    }
}
