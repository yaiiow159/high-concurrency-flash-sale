package com.flashsale.application.port.out;

import com.flashsale.domain.identity.RefreshToken;

import java.time.Instant;
import java.util.Optional;

/** Refresh token 持久化埠（出站）。 */
public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** 撤銷整條輪替鏈。 */
    int revokeFamily(String familyId, Instant revokedAt);

    /** 撤銷某使用者的所有 token，供停權與「登出所有裝置」使用。 */
    int revokeAllForUser(Long userId, Instant revokedAt);

    /** 清除已過期的紀錄。 */
    int deleteExpiredBefore(Instant threshold);
}
