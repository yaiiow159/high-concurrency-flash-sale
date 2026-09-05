package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 發給使用者的券。
 *
 * <p><b>沒有狀態變更方法。</b> 核銷走的是一句條件式 UPDATE
 * （見 {@code CouponJpaRepository.redeem}）——透過實體改狀態的話，
 * 「讀出來、判斷、改、寫回」中間有窗口，兩個併發請求會讓同一張券用兩次。
 */
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

    /**
     * 自行領取的憑據 {@code {userId}:{promotionId}}；管理員發放為 {@code null}。
     *
     * <p>唯一索引建在這一欄而不是 {@code (user_id, promotion_id)}——
     * 既有資料裡已經有人持有同一促銷的兩張券（補發、補償），
     * 而 MySQL 的唯一索引允許多個 NULL，剛好只約束自行領取的那些。
     */
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
