package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.PromotionRepository;
import com.flashsale.domain.promotion.Coupon;
import com.flashsale.domain.promotion.CouponStatus;
import com.flashsale.domain.promotion.DiscountType;
import com.flashsale.domain.promotion.Promotion;
import com.flashsale.domain.promotion.PromotionRule;
import com.flashsale.infrastructure.adapter.out.persistence.entity.CouponEntity;
import com.flashsale.infrastructure.adapter.out.persistence.entity.PromotionEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.CouponJpaRepository;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.PromotionJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.UUID;

/** 優惠與券持久化埠的 JPA 實作。 */
@Repository
public class JpaPromotionRepository implements PromotionRepository {

    private final PromotionJpaRepository promotionJpaRepository;
    private final CouponJpaRepository couponJpaRepository;

    public JpaPromotionRepository(PromotionJpaRepository promotionJpaRepository,
                                  CouponJpaRepository couponJpaRepository) {
        this.promotionJpaRepository = promotionJpaRepository;
        this.couponJpaRepository = couponJpaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Promotion> findActivePromotions(Instant now) {
        return promotionJpaRepository.findActive(now).stream()
                .map(JpaPromotionRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Promotion> findPromotionById(Long promotionId) {
        return promotionJpaRepository.findById(promotionId).map(JpaPromotionRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Promotion> findPromotionsByIds(List<Long> promotionIds) {
        if (promotionIds.isEmpty()) {
            // 空集合會產生 `in ()` 這種在部分資料庫上非法的 SQL
            return Map.of();
        }
        return promotionJpaRepository.findAllById(promotionIds).stream()
                .map(JpaPromotionRepository::toDomain)
                .collect(Collectors.toMap(Promotion::id, promotion -> promotion,
                        (first, second) -> first));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Coupon> findCoupon(Long couponId) {
        return couponJpaRepository.findById(couponId).map(JpaPromotionRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Coupon> findUsableCoupons(Long userId, Instant now) {
        return couponJpaRepository.findUsable(userId, now).stream()
                .map(JpaPromotionRepository::toDomain)
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code MANDATORY}：核銷必須跟著下單交易一起成功或一起回滾（ADR-0013 決策 7）。
     * 自己開交易的話，訂單建立失敗時券已經核銷掉了——使用者的券白白消失。
     * 用 {@code REQUIRED} 表面上也能達到同樣效果，但那會安靜地接受
     * 「有人在交易外呼叫」，而那正是這裡最不能發生的事。
     * 與 {@code EventOutbox.append} 同一個理由。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean redeem(Long couponId, String orderNo, Instant usedAt) {
        return couponJpaRepository.redeem(couponId, orderNo, usedAt) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Promotion> findExchangeable(Instant now) {
        return promotionJpaRepository.findExchangeable(now).stream()
                .map(JpaPromotionRepository::toDomain)
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>券號用 {@code EX-} 前綴加 UUID 片段。<b>不用流水號</b>：
     * 券號會被使用者看到也會被貼出來，可預測的號碼等於讓人猜得到別人的券。
     * 猜到也用不了（核銷會檢查擁有者），但那不是把它做成可猜的理由。
     *
     * <p>有效期 30 天。寫死在這裡而不是設定檔——它是券的一部分，
     * 改它等於改所有已發出的券的預期，值得一次明確的程式碼變更。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String issueCoupon(Long userId, Long promotionId, Instant expiresAt) {
        String code = "EX-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        couponJpaRepository.saveAndFlush(new CouponEntity(
                userId, promotionId, code, CouponStatus.ISSUED.name(), expiresAt));
        return code;
    }

    private static Promotion toDomain(PromotionEntity entity) {
        return Promotion.of(entity.getId(), entity.getName(),
                DiscountType.valueOf(entity.getType()),
                PromotionRule.valueOf(entity.getRule()),
                entity.getThreshold(), entity.getValue(), entity.getMaxDiscount(),
                entity.getStartAt(), entity.getEndAt(), entity.isEnabled(),
                entity.getPointCost());
    }

    private static Coupon toDomain(CouponEntity entity) {
        return Coupon.restore(entity.getId(), entity.getUserId(), entity.getPromotionId(),
                entity.getCode(), CouponStatus.valueOf(entity.getStatus()),
                entity.getExpiresAt(), entity.getUsedOrderNo());
    }
}
