package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "user_blacklist")
public class UserBlacklistEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "reason", nullable = false, length = 200)
    private String reason;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected UserBlacklistEntity() {
    }

    public UserBlacklistEntity(Long userId, String reason, Long createdBy, Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.reason = reason;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void overwrite(String reason, Long createdBy, Instant createdAt, Instant expiresAt) {
        this.reason = reason;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Long getUserId() {
        return userId;
    }

    public String getReason() {
        return reason;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
