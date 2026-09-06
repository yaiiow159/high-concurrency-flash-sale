package com.flashsale.domain.promotion;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/** 發給特定使用者的一張券。 */
public final class Coupon {

    private final Long id;
    private final Long userId;
    private final Long promotionId;
    private final String code;
    private final CouponStatus status;
    private final Instant expiresAt;
    private final String usedOrderNo;

    private Coupon(Long id, Long userId, Long promotionId, String code,
                   CouponStatus status, Instant expiresAt, String usedOrderNo) {
        this.id = id;
        this.userId = Objects.requireNonNull(userId, "userId 不可為 null");
        this.promotionId = Objects.requireNonNull(promotionId, "promotionId 不可為 null");
        this.code = Objects.requireNonNull(code, "code 不可為 null");
        this.status = Objects.requireNonNull(status, "status 不可為 null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt 不可為 null");
        this.usedOrderNo = usedOrderNo;
    }

    public static Coupon issue(Long userId, Long promotionId, String code, Instant expiresAt) {
        return new Coupon(null, userId, promotionId, code, CouponStatus.ISSUED, expiresAt, null);
    }

    public static Coupon restore(Long id, Long userId, Long promotionId, String code,
                                 CouponStatus status, Instant expiresAt, String usedOrderNo) {
        return new Coupon(id, userId, promotionId, code, status, expiresAt, usedOrderNo);
    }

    /** 前置檢查，給出精確的拒絕理由。 */
    public void ensureUsableBy(Long candidateUserId, Instant now) {
        if (!userId.equals(candidateUserId)) {
            // 回「不存在」而非「不是你的」：後者等於確認這個券號是有效的，
            // 讓人可以靠窮舉找出別人的券
            throw new BusinessException(ErrorCode.COUPON_NOT_FOUND, "優惠券不存在");
        }
        if (status == CouponStatus.USED) {
            throw new BusinessException(ErrorCode.COUPON_ALREADY_USED, "這張優惠券已經使用過了");
        }
        if (status == CouponStatus.EXPIRED || !now.isBefore(expiresAt)) {
            throw new BusinessException(ErrorCode.COUPON_EXPIRED, "這張優惠券已過期");
        }
    }

    public boolean isUsable(Instant now) {
        return status == CouponStatus.ISSUED && now.isBefore(expiresAt);
    }

    public Long id() {
        return id;
    }

    public Long userId() {
        return userId;
    }

    public Long promotionId() {
        return promotionId;
    }

    public String code() {
        return code;
    }

    public CouponStatus status() {
        return status;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public String usedOrderNo() {
        return usedOrderNo;
    }
}
