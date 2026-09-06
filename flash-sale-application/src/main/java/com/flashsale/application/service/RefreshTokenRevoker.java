package com.flashsale.application.service;

import com.flashsale.application.port.out.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 在<b>獨立交易</b>（{@code REQUIRES_NEW}）中撤銷令牌。
 *
 * <p>重用偵測是「撤銷輪替鏈 → 拋例外拒絕請求」，而那個例外會讓外層交易回滾，
 * 把撤銷一起還原掉——偵測到外洩卻什麼都沒撤銷。
 */
@Service
public class RefreshTokenRevoker {

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenRevoker(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /**
     * 撤銷整條輪替鏈，並在本方法回傳時即已 commit。
     *
     * @return 本次撤銷的筆數
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(String familyId, Instant revokedAt) {
        return refreshTokenRepository.revokeFamily(familyId, revokedAt);
    }

    /** 撤銷某使用者的所有令牌，供停權與「登出所有裝置」使用。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForUser(Long userId, Instant revokedAt) {
        return refreshTokenRepository.revokeAllForUser(userId, revokedAt);
    }
}
