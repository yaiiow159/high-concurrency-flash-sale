package com.flashsale.application.service;

import com.flashsale.application.port.out.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 在<b>獨立交易</b>中撤銷令牌。
 *
 * <p><b>為什麼需要這個類別？</b>
 * 重用偵測的流程是「撤銷整條輪替鏈 → 拋例外拒絕本次請求」。
 * 但 {@code BusinessException} 是 RuntimeException，會讓外層交易回滾——
 * <b>把剛才的撤銷一起還原掉</b>。結果是偵測到了外洩卻什麼都沒撤銷，
 * 攻擊者手上的令牌照樣能用。
 *
 * <p>{@code REQUIRES_NEW} 讓撤銷跑在自己的交易裡，先行 commit，
 * 不受外層回滾影響。
 *
 * <p>拆成獨立 Bean 是必要的：Spring 的交易靠動態代理，
 * 同一個 Bean 內呼叫 {@code this.method()} 不會經過代理，
 * {@code REQUIRES_NEW} 會安靜失效——與 {@code OutboxRelayScheduler}
 * 和 {@code OutboxRelayer} 拆開的理由完全相同。
 *
 * <p><b>這個 bug 是實機驗證才發現的</b>：mock 單元測試會看到
 * {@code revokeFamily} 確實被呼叫而判定通過，但攔不到交易回滾。
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
