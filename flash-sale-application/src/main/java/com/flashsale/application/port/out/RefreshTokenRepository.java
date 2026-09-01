package com.flashsale.application.port.out;

import com.flashsale.domain.identity.RefreshToken;

import java.time.Instant;
import java.util.Optional;

/** Refresh token 持久化埠（出站）。 */
public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 撤銷整條輪替鏈。
     *
     * <p>重用偵測觸發時呼叫——無法分辨誰是竊取者，因此讓雙方都重新登入。
     *
     * @return 本次撤銷的筆數
     */
    int revokeFamily(String familyId, Instant revokedAt);

    /** 撤銷某使用者的所有 token，供停權與「登出所有裝置」使用。 */
    int revokeAllForUser(Long userId, Instant revokedAt);

    /**
     * 清除已過期的紀錄。
     *
     * <p>這張表的寫入量等同登入次數乘以 refresh 頻率，不清理會持續成長，
     * 且 {@code findByTokenHash} 的索引會越來越大。
     */
    int deleteExpiredBefore(Instant threshold);
}
