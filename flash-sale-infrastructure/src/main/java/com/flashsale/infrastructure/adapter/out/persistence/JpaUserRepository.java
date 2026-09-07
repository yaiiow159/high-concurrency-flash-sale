package com.flashsale.infrastructure.adapter.out.persistence;

import org.springframework.data.domain.PageRequest;
import java.util.List;
import com.flashsale.application.port.out.UserRepository;
import com.flashsale.domain.identity.Email;
import com.flashsale.domain.identity.PasswordHash;
import com.flashsale.domain.identity.User;
import com.flashsale.domain.identity.UserRole;
import com.flashsale.domain.identity.UserStatus;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.infrastructure.adapter.out.persistence.entity.UserEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.UserJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** 使用者持久化埠的 JPA 實作。 */
@Repository
public class JpaUserRepository implements UserRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaUserRepository.class);

    private final UserJpaRepository jpaRepository;

    public JpaUserRepository(UserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Optional<User> createIfAbsent(User user) {
        if (jpaRepository.existsByEmail(user.email().value())) {
            return Optional.empty();
        }
        try {
            UserEntity saved = jpaRepository.saveAndFlush(new UserEntity(
                    user.email().value(),
                    user.passwordHash().value(),
                    user.displayName(),
                    user.role().name(),
                    user.status().name(),
                    user.createdAt()));
            return Optional.of(toDomain(saved));
        } catch (DataIntegrityViolationException e) {
            log.debug("信箱 {} 觸發唯一鍵衝突，判定為重複註冊", user.email().masked());
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public User update(User user) {
        UserEntity entity = jpaRepository.findById(user.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        entity.applyChanges(
                user.passwordHash().value(),
                user.displayName(),
                user.role().name(),
                user.status().name());
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<Long, String> findDisplayNames(java.util.Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, String> names = new java.util.HashMap<>();
        for (Object[] row : jpaRepository.findDisplayNamesByIds(userIds)) {
            names.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        return names;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByRole(UserRole role) {
        return jpaRepository.existsByRole(role.name());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByEmail(Email email) {
        return jpaRepository.findByEmail(email.value()).map(JpaUserRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findById(Long userId) {
        return jpaRepository.findById(userId).map(JpaUserRepository::toDomain);
    }

    private static User toDomain(UserEntity entity) {
        return User.restore(
                entity.getId(),
                Email.of(entity.getEmail()),
                new PasswordHash(entity.getPasswordHash()),
                entity.getDisplayName(),
                UserRole.valueOf(entity.getRole()),
                UserStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getVersion());
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> search(String keyword, UserStatus status, int limit, int offset) {
        return jpaRepository.search(likePattern(keyword), status == null ? null : status.name(),
                        PageRequest.of(offset / Math.max(limit, 1), Math.max(limit, 1)))
                .stream()
                .map(JpaUserRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countSearch(String keyword, UserStatus status) {
        return jpaRepository.countSearch(likePattern(keyword), status == null ? null : status.name());
    }

    /** 前綴比對：信箱那一欄有唯一索引，`abc%` 走得到、`%abc%` 走不到。 */
    private static String likePattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim().replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
