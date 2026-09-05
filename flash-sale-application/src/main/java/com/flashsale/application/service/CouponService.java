package com.flashsale.application.service;

import com.flashsale.application.port.in.CouponQueryUseCase;
import com.flashsale.application.port.in.dto.ClaimableCouponView;
import com.flashsale.application.port.in.dto.CouponView;
import com.flashsale.application.port.out.PromotionRepository;
import com.flashsale.domain.promotion.Coupon;
import com.flashsale.domain.promotion.Promotion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 優惠券的查詢與領取。
 *
 * <h2>為什麼從 OrderPlacementService 搬出來</h2>
 *
 * <p>「我有哪些券」先前掛在下單服務上，而它與下單沒有任何關係——
 * 那個方法只用到 {@code promotionRepository} 與 {@code clock}。
 * 領券中心需要一個放它的地方，而把領券也塞進下單服務
 * 只會讓那個類別更難說清楚它到底負責什麼。
 */
@Service
public class CouponService implements CouponQueryUseCase {

    private static final Logger log = LoggerFactory.getLogger(CouponService.class);

    /** 自行領取的券的有效天數。 */
    private static final int CLAIMED_COUPON_VALID_DAYS = 30;

    private final PromotionRepository promotionRepository;
    private final java.time.Clock clock;

    public CouponService(PromotionRepository promotionRepository, java.time.Clock clock) {
        this.promotionRepository = promotionRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CouponView> myUsableCoupons(Long userId) {
        Instant now = clock.instant();
        List<Coupon> coupons = promotionRepository.findUsableCoupons(userId, now);
        if (coupons.isEmpty()) {
            return List.of();
        }

        // **一次批次查完規則。** 先前是逐張券呼叫 findPromotionById，
        // 而這支在結帳頁載入時就會被打——手上有 20 張券就是 20 次往返。
        // 多張券共用同一個規則是常態（同一檔活動發給很多人），
        // 批次查連重複的部分都省掉了。
        Map<Long, Promotion> rules = promotionRepository.findPromotionsByIds(
                coupons.stream().map(Coupon::promotionId).distinct().toList());

        return coupons.stream()
                .flatMap(coupon -> Optional.ofNullable(rules.get(coupon.promotionId()))
                        // 規則被硬刪時券就沒有意義了。跳過而不是拋例外——
                        // 一張壞掉的券不該讓使用者連結帳頁都打不開
                        .filter(promotion -> promotion.isApplicableAt(now))
                        .map(promotion -> CouponView.of(coupon, promotion))
                        .stream())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClaimableCouponView> claimable(Long userId) {
        Set<Long> claimed = promotionRepository.findClaimedPromotionIds(userId);
        return promotionRepository.findClaimablePromotions(clock.instant()).stream()
                // 已經領過的**仍然回傳**，只是標記起來讓按鈕變成「已領取」。
                // 從清單裡拿掉會讓使用者以為活動結束了，然後跑去問客服
                .map(promotion -> ClaimableCouponView.of(promotion,
                        claimed.contains(promotion.id())))
                .toList();
    }

    @Override
    public boolean claim(Long userId, Long promotionId) {
        Instant now = clock.instant();
        Promotion promotion = promotionRepository.findPromotionById(promotionId)
                .filter(candidate -> candidate.isApplicableAt(now))
                .orElseThrow(() -> new com.flashsale.domain.shared.BusinessException(
                        com.flashsale.domain.shared.ErrorCode.PROMOTION_NOT_FOUND,
                        "活動不存在或已結束"));

        // 有效期取「活動結束」與「領取後 30 天」的**較早者**。
        // 只看 30 天的話，活動結束後券還能用；只看活動結束的話，
        // 活動最後一天領到的券當天就過期，那等於沒發
        Instant expiresAt = earlier(promotion.endAt(),
                now.plus(java.time.Duration.ofDays(CLAIMED_COUPON_VALID_DAYS)));

        boolean claimed = promotionRepository.claimCoupon(userId, promotionId, expiresAt);
        if (claimed) {
            log.info("使用者 {} 領取優惠券 promotionId={}", userId, promotionId);
        }
        return claimed;
    }

    private static Instant earlier(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }
}
