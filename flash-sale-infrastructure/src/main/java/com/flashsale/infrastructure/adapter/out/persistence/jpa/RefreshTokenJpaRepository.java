package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/** Refresh token 的 Spring Data 介面。 */
public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, Long> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    /** 撤銷整條輪替鏈。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshTokenEntity t set t.revokedAt = :revokedAt
            where t.familyId = :familyId and t.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") String familyId, @Param("revokedAt") Instant revokedAt);

    /** 撤銷某使用者的所有 token，供停權與「登出所有裝置」使用。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshTokenEntity t set t.revokedAt = :revokedAt
            where t.userId = :userId and t.revokedAt is null
            """)
    int revokeAllForUser(@Param("userId") Long userId, @Param("revokedAt") Instant revokedAt);

    /** 清除已過期的紀錄。 */
    @Modifying
    @Query("delete from RefreshTokenEntity t where t.expiresAt < :threshold")
    int deleteExpiredBefore(@Param("threshold") Instant threshold);
}
