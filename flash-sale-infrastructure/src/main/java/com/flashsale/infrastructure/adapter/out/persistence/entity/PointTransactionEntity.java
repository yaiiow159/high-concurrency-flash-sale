package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 一筆積分流水。
 *
 * <p><b>全部欄位 {@code updatable = false}</b>：流水是只增不改的。
 * 記錯了要用一筆反向的 {@code ADJUSTMENT} 沖銷，而不是回頭改那一列——
 * 改掉的話，「當時到底發生什麼事」就永遠查不出來了。
 */
@Entity
@Table(name = "point_transaction")
public class PointTransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "delta", nullable = false, updatable = false)
    private long delta;

    @Column(name = "balance_after", nullable = false, updatable = false)
    private long balanceAfter;

    @Column(name = "reason", nullable = false, length = 24, updatable = false)
    private String reason;

    @Column(name = "ref_no", nullable = false, length = 64, updatable = false)
    private String refNo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PointTransactionEntity() {
        // JPA 專用
    }

    public PointTransactionEntity(Long userId, long delta, long balanceAfter,
                                  String reason, String refNo, Instant createdAt) {
        this.userId = userId;
        this.delta = delta;
        this.balanceAfter = balanceAfter;
        this.reason = reason;
        this.refNo = refNo;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public long getDelta() {
        return delta;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }

    public String getReason() {
        return reason;
    }

    public String getRefNo() {
        return refNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
