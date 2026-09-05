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

    /**
     * 批次取規則，供「我的優惠券」使用。
     *
     * <p>存在的唯一理由是避免 N+1：使用者手上有 20 張券，逐張查規則就是
     * 20 次往返，而這支在結帳頁載入時就會被打。
     *
     * <p>回傳的 Map <b>只包含查得到的</b>——規則被硬刪時那張券就沒有意義了，
     * 呼叫端跳過它而不是拋例外。
     */
    Map<Long, Promotion> findPromotionsByIds(List<Long> promotionIds);

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

    /** 開放用積分兌換的優惠。 */
    List<Promotion> findExchangeable(Instant now);

    /**
     * 發一張券給使用者。
     *
     * <p>{@code MANDATORY} 語意：發券必須跟著扣點一起成功或一起回滾。
     * 分開做的話，先扣點後發券則扣了點沒拿到券，
     * 先發券後扣點則拿到券卻沒扣點——後者是可以無限重複的。
     *
     * @return 券號，前端要顯示給使用者
     */
    String issueCoupon(Long userId, Long promotionId, Instant expiresAt);

    /**
     * 領券中心上可以領的促銷：進行中、未結束、且是 {@code COUPON} 型。
     *
     * <p>與 {@link #findActivePromotions} 分開：後者回的是「下單時會自動套用的」
     * （滿額折、免運），而那些**不需要領**。混在一起的話，
     * 領券中心會列出一堆按不下去的東西。
     */
    List<Promotion> findClaimablePromotions(Instant now);

    /** 這個人已經自行領過哪些促銷。用來把清單上的按鈕標成「已領取」。 */
    Set<Long> findClaimedPromotionIds(Long userId);

    /**
     * 自行領一張券。
     *
     * <p><b>靠唯一索引擋重複，不先查再寫。</b> 先查再寫是 read-modify-write，
     * 兩個並行的領取請求都會通過檢查然後各發一張。
     *
     * <p>管理員發放（{@link #issueCoupon}）不受這個限制——
     * 它寫進去的 {@code claim_key} 是 NULL，而 MySQL 的唯一索引允許多個 NULL。
     *
     * @return 這次真的領到了才回 {@code true}；已經領過回 {@code false}
     */
    boolean claimCoupon(Long userId, Long promotionId, Instant expiresAt);
}
