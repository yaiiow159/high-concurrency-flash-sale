package com.flashsale.application.port.out;

import com.flashsale.domain.promotion.Coupon;
import com.flashsale.domain.promotion.Promotion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 優惠與券的持久化埠（出站）。 */
public interface PromotionRepository {

    /** 目前生效中、且不需要券的優惠。券的規則另外經由 {@link #findCoupon} 取得。 */
    List<Promotion> findActivePromotions(Instant now);

    Optional<Promotion> findPromotionById(Long promotionId);

    Optional<Coupon> findCoupon(Long couponId);

    /** 使用者手上還沒用、也還沒過期的券。 */
    List<Coupon> findUsableCoupons(Long userId, Instant now);

    /**
     * 核銷一張券。
     *
     * <p><b>必須是單一條件式 UPDATE</b>（ADR-0013 決策 6）：
     *
     * <pre>
     *   UPDATE coupon SET status='USED', ... WHERE id=? AND status='ISSUED'
     * </pre>
     *
     * <p>「查券沒用過 → 建立訂單 → 標記已使用」是 read-modify-write，
     * 兩個併發請求會讓同一張券用兩次。把檢查與寫入合成一個語句，
     * 中間就插不進東西——與 {@code InventoryJpaRepository.deductAvailable} 同一個手法。
     *
     * <p><b>不要用分散式鎖</b>：臨界區完全可以原子化，加鎖只是多一個會失敗的東西。
     *
     * @return {@code true} 表示這次真的核銷了；{@code false} 代表已被用掉
     */
    boolean redeem(Long couponId, String orderNo, Instant usedAt);
}
