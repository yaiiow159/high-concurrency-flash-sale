package com.flashsale.application.port.out;

import com.flashsale.domain.identity.User;

import java.time.Duration;

/** Access token 簽發埠（出站）。 */
public interface AccessTokenIssuer {

    IssuedAccessToken issue(User user);

    record IssuedAccessToken(String value, Duration expiresIn) {
    }
}
