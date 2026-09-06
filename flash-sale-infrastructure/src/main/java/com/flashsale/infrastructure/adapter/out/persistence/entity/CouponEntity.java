package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 發給使用者的券。 */
@Entity
@Table(name = "coupon")
public class CouponEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "promotion_id", nullable = false, updatable = false)
    private Long promotionId;

    @Column(name = "code", nullable = false, length = 32, updatable = false)
    private String code;

    /** 自行領取的憑據 {@code {userId}:{promotionId}}；管理員發放為 {@code null}。 */
    @Column(name = "claim_key", length = 64, updatable = false)
    private String claimKey;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_order_no", length = 64)
    private String usedOrderNo;

    @Column(name = "used_at")
    private Instant usedAt;

    protected CouponEntity() {
        // JPA 專用
    }

    public CouponEntity(Long userId, Long promotionId, String code,
                        String status, Instant expiresAt) {
        this.userId = userId;
        this.promotionId = promotionId;
        this.code = code;
        this.status = status;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPromotionId() {
        return promotionId;
    }

    public String getCode() {
        return code;
    }

    public String getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getUsedOrderNo() {
        return usedOrderNo;
    }
}
