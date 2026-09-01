package com.flashsale.application.port.out;

import com.flashsale.domain.identity.User;

import java.time.Duration;

/**
 * Access token 簽發埠（出站）。
 *
 * <p>應用層只知道「要為這個使用者發一張短期憑證」，
 * 不知道那是 JWT、用什麼演算法簽、claim 長什麼樣——那些都是實作細節。
 * 日後換成非對稱簽章或改用外部 IdP，應用層一行都不用動。
 */
public interface AccessTokenIssuer {

    IssuedAccessToken issue(User user);

    record IssuedAccessToken(String value, Duration expiresIn) {
    }
}
