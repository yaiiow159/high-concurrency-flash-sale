package com.flashsale.infrastructure.adapter.out.persistence;

import jakarta.persistence.PersistenceContext;
import jakarta.persistence.EntityManager;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.UUID;

/** 優惠與券持久化埠的 JPA 實作。 */
@Repository
public class JpaPromotionRepository implements PromotionRepository {

    /** 領券用原生 upsert，走不了具名查詢。 */
    @PersistenceContext
    private EntityManager entityManager;

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

    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String issueCoupon(Long userId, Long promotionId, Instant expiresAt) {
        String code = "EX-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        couponJpaRepository.saveAndFlush(new CouponEntity(
                userId, promotionId, code, CouponStatus.ISSUED.name(), expiresAt));
        return code;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Promotion> findClaimablePromotions(Instant now) {
        return promotionJpaRepository
                .findByTypeAndEnabledTrueAndStartAtBeforeAndEndAtAfterOrderByEndAtAsc(
                        DiscountType.COUPON.name(), now, now)
                .stream()
                .map(JpaPromotionRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> findClaimedPromotionIds(Long userId) {
        return Set.copyOf(couponJpaRepository.findClaimedPromotionIds(userId));
    }

    /** 自行領一張券。 */
    @Override
    @Transactional
    public boolean claimCoupon(Long userId, Long promotionId, Instant expiresAt) {
        String code = "CL-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        String claimKey = userId + ":" + promotionId;

        entityManager.createNativeQuery("""
                        insert into coupon
                            (user_id, promotion_id, code, claim_key, status, expires_at, created_at)
                        values (:userId, :promotionId, :code, :claimKey, :status, :expiresAt, now(3))
                        on duplicate key update claim_key = claim_key
                        """)
                .setParameter("userId", userId)
                .setParameter("promotionId", promotionId)
                .setParameter("code", code)
                .setParameter("claimKey", claimKey)
                .setParameter("status", CouponStatus.ISSUED.name())
                .setParameter("expiresAt", java.sql.Timestamp.from(expiresAt))
                .executeUpdate();

        Object stored = entityManager.createNativeQuery(
                        "select code from coupon where claim_key = :claimKey")
                .setParameter("claimKey", claimKey)
                .getSingleResult();
        return code.equals(stored);
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
