package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.BlacklistRepository;
import com.flashsale.domain.risk.BlacklistEntry;
import com.flashsale.infrastructure.adapter.out.persistence.entity.UserBlacklistEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.UserBlacklistJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JpaBlacklistRepository implements BlacklistRepository {

    private final UserBlacklistJpaRepository jpaRepository;

    public JpaBlacklistRepository(UserBlacklistJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BlacklistEntry> findActive(Long userId, Instant now) {
        return jpaRepository.findById(userId)
                .map(JpaBlacklistRepository::toDomain)
                .filter(entry -> entry.isActiveAt(now));
    }

    @Override
    @Transactional
    public BlacklistEntry save(BlacklistEntry entry) {
        UserBlacklistEntity entity = jpaRepository.findById(entry.userId())
                .map(existing -> {
                    existing.overwrite(entry.reason(), entry.createdBy(), entry.createdAt(), entry.expiresAt());
                    return existing;
                })
                .orElseGet(() -> new UserBlacklistEntity(
                        entry.userId(), entry.reason(), entry.createdBy(), entry.createdAt(), entry.expiresAt()));
        return toDomain(jpaRepository.saveAndFlush(entity));
    }

    @Override
    @Transactional
    public void delete(Long userId) {
        jpaRepository.deleteById(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BlacklistEntry> findAll(int limit, int offset) {
        return jpaRepository.findAllNewestFirst(PageRequest.of(offset / Math.max(limit, 1), Math.max(limit, 1)))
                .stream()
                .map(JpaBlacklistRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return jpaRepository.count();
    }

    private static BlacklistEntry toDomain(UserBlacklistEntity entity) {
        return new BlacklistEntry(entity.getUserId(), entity.getReason(), entity.getCreatedBy(),
                entity.getCreatedAt(), entity.getExpiresAt());
    }
}
