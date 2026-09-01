package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Refresh token 的持久化模型。
 *
 * <p>只存雜湊，不存原值——資料庫外洩時攻擊者拿到雜湊也換不到新令牌。
 */
@Entity
@Table(name = "refresh_token",
        uniqueConstraints = @UniqueConstraint(name = "uk_token_hash", columnNames = "token_hash"),
        indexes = {
                // 重用偵測時要一次撤銷整條輪替鏈
                @Index(name = "idx_rt_family", columnList = "family_id"),
                // 停權與「登出所有裝置」
                @Index(name = "idx_rt_user", columnList = "user_id"),
                // 過期清理排程
                @Index(name = "idx_rt_expires", columnList = "expires_at")
        })
public class RefreshTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 的十六進位表示，固定 64 字元。 */
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "family_id", nullable = false, length = 32, updatable = false)
    private String familyId;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** 非 null 即代表已被輪替；此時再被使用就是重用，要撤銷整條鏈。 */
    @Column(name = "replaced_by_hash", length = 64)
    private String replacedByHash;

    protected RefreshTokenEntity() {
        // JPA 專用
    }

    public RefreshTokenEntity(String tokenHash, Long userId, String familyId,
                              Instant issuedAt, Instant expiresAt,
                              Instant revokedAt, String replacedByHash) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.familyId = familyId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
        this.replacedByHash = replacedByHash;
    }

    public void applyChanges(Instant revokedAt, String replacedByHash) {
        this.revokedAt = revokedAt;
        this.replacedByHash = replacedByHash;
    }

    public Long getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Long getUserId() {
        return userId;
    }

    public String getFamilyId() {
        return familyId;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getReplacedByHash() {
        return replacedByHash;
    }
}
