package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.RefreshTokenRepository;
import com.flashsale.domain.identity.RefreshToken;
import com.flashsale.infrastructure.adapter.out.persistence.entity.RefreshTokenEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.RefreshTokenJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** Refresh token 持久化埠的 JPA 實作。 */
@Repository
public class JpaRefreshTokenRepository implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpaRepository;

    public JpaRefreshTokenRepository(RefreshTokenJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public RefreshToken save(RefreshToken token) {
        RefreshTokenEntity entity = token.id() == null
                ? new RefreshTokenEntity(token.tokenHash(), token.userId(), token.familyId(),
                        token.issuedAt(), token.expiresAt(), token.revokedAt(), token.replacedByHash())
                : loadAndApply(token);
        return toDomain(jpaRepository.save(entity));
    }

    private RefreshTokenEntity loadAndApply(RefreshToken token) {
        RefreshTokenEntity entity = jpaRepository.findById(token.id())
                .orElseThrow(() -> new IllegalStateException(
                        "更新 refresh token 時找不到紀錄 id=" + token.id()));
        entity.applyChanges(token.revokedAt(), token.replacedByHash());
        return entity;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash).map(JpaRefreshTokenRepository::toDomain);
    }

    @Override
    @Transactional
    public int revokeFamily(String familyId, Instant revokedAt) {
        return jpaRepository.revokeFamily(familyId, revokedAt);
    }

    @Override
    @Transactional
    public int revokeAllForUser(Long userId, Instant revokedAt) {
        return jpaRepository.revokeAllForUser(userId, revokedAt);
    }

    @Override
    @Transactional
    public int deleteExpiredBefore(Instant threshold) {
        return jpaRepository.deleteExpiredBefore(threshold);
    }

    private static RefreshToken toDomain(RefreshTokenEntity entity) {
        return RefreshToken.restore(
                entity.getId(),
                entity.getTokenHash(),
                entity.getUserId(),
                entity.getFamilyId(),
                entity.getIssuedAt(),
                entity.getExpiresAt(),
                entity.getRevokedAt(),
                entity.getReplacedByHash());
    }
}
