package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.CouponEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CouponJpaRepository extends JpaRepository<CouponEntity, Long> {

    @Query("""
            select c from CouponEntity c
             where c.userId = :userId
               and c.status = 'ISSUED'
               and c.expiresAt > :now
             order by c.expiresAt
            """)
    List<CouponEntity> findUsable(@Param("userId") Long userId, @Param("now") Instant now);

    /** 核銷一張券（ADR-0013 決策 6）。 */
    @Modifying
    @Query("""
            update CouponEntity c
               set c.status = 'USED',
                   c.usedOrderNo = :orderNo,
                   c.usedAt = :now
             where c.id = :couponId
               and c.status = 'ISSUED'
               and c.expiresAt > :now
            """)
    int redeem(@Param("couponId") Long couponId,
               @Param("orderNo") String orderNo,
               @Param("now") Instant now);
    /** 這個人自行領過哪些促銷。管理員發放的 claim_key 是 NULL，不算在內。 */
    @Query("select c.promotionId from CouponEntity c where c.userId = :userId and c.claimKey is not null")
    List<Long> findClaimedPromotionIds(@Param("userId") Long userId);

}
