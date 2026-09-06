package com.flashsale.application.port.out;

import com.flashsale.domain.promotion.Coupon;
import com.flashsale.domain.promotion.Promotion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 優惠與券的持久化埠（出站）。 */
public interface PromotionRepository {

    /** 目前生效中、且不需要券的優惠。券的規則另外經由 {@link #findCoupon} 取得。 */
    List<Promotion> findActivePromotions(Instant now);

    Optional<Promotion> findPromotionById(Long promotionId);

    /** 批次取規則，供「我的優惠券」使用。 */
    Map<Long, Promotion> findPromotionsByIds(List<Long> promotionIds);

    Optional<Coupon> findCoupon(Long couponId);

    /** 使用者手上還沒用、也還沒過期的券。 */
    List<Coupon> findUsableCoupons(Long userId, Instant now);

    /** 核銷一張券。 */
    boolean redeem(Long couponId, String orderNo, Instant usedAt);

    /** 開放用積分兌換的優惠。 */
    List<Promotion> findExchangeable(Instant now);

    /** 發一張券給使用者。 */
    String issueCoupon(Long userId, Long promotionId, Instant expiresAt);

    /** 領券中心上可以領的促銷：進行中、未結束、且是 {@code COUPON} 型。 */
    List<Promotion> findClaimablePromotions(Instant now);

    /** 這個人已經自行領過哪些促銷。用來把清單上的按鈕標成「已領取」。 */
    Set<Long> findClaimedPromotionIds(Long userId);

    /** 自行領一張券。 */
    boolean claimCoupon(Long userId, Long promotionId, Instant expiresAt);
}
