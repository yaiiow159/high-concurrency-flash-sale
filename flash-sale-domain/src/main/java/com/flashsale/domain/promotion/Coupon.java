package com.flashsale.domain.promotion;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/**
 * 發給特定使用者的一張券。
 *
 * <h2>券本身不知道怎麼算折扣</h2>
 *
 * <p>折抵規則在它引用的 {@link Promotion} 上。券只回答三件事：
 * 是誰的、還能不能用、什麼時候到期。
 *
 * <p>這個切分讓「同一個優惠發給一千個人」不必複製一千份規則，
 * 而修改規則時也不會出現「有些券按新規則、有些按舊規則」——
 * 那正是訂單為什麼要存折扣<b>快照</b>的理由：規則會變，已成立的交易不能跟著變。
 *
 * <h2>核銷不在這裡做</h2>
 *
 * <p>「檢查沒用過 → 標記已使用」是 read-modify-write，
 * 在聚合根上做無法阻止兩個併發請求都通過檢查。
 * 核銷是一句條件式 UPDATE，在儲存庫層完成（ADR-0013 決策 6）。
 * 這個聚合根上的 {@link #ensureUsableBy} 是<b>前置檢查</b>，
 * 用來給出精確的錯誤訊息，不是併發防線。
 */
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

    /**
     * 前置檢查，給出精確的拒絕理由。
     *
     * <p><b>這不是併發防線</b>——真正擋住重複核銷的是儲存庫那句條件式 UPDATE。
     * 這裡存在的意義是讓使用者看到「券已使用」而不是一個含糊的失敗。
     */
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
