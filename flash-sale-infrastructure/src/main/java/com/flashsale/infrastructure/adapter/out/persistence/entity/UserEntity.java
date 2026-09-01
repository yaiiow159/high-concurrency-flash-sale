package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 使用者的持久化模型。
 *
 * <p>表名用 {@code app_user} 而非 {@code user}：後者在 MySQL 與 PostgreSQL
 * 都是保留字，得靠引號才能查詢，維運時每一句 SQL 都要記得加引號。
 */
@Entity
@Table(name = "app_user",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_email", columnNames = "email"))
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 已正規化為小寫，唯一索引才擋得住大小寫不同的重複註冊。 */
    @Column(name = "email", nullable = false, length = 254, updatable = false)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected UserEntity() {
        // JPA 專用
    }

    public UserEntity(String email, String passwordHash, String displayName,
                      String role, String status, Instant createdAt) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
        this.status = status;
        this.createdAt = createdAt;
    }

    public void applyChanges(String passwordHash, String displayName, String role, String status) {
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRole() {
        return role;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
