package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.PromotionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PromotionJpaRepository extends JpaRepository<PromotionEntity, Long> {

    /** 目前生效中、且不需要券的優惠。 */
    @Query("""
            select p from PromotionEntity p
             where p.enabled = true
               and p.type <> 'COUPON'
               and p.startAt <= :now
               and p.endAt > :now
             order by p.id
            """)
    List<PromotionEntity> findActive(@Param("now") Instant now);

    /** 開放用積分兌換的優惠。 */
    @Query("""
            select p from PromotionEntity p
             where p.enabled = true
               and p.pointCost > 0
               and p.startAt <= :now
               and p.endAt > :now
             order by p.pointCost
            """)
    List<PromotionEntity> findExchangeable(@Param("now") Instant now);
    /** 領券中心可以領的促銷。 */
    List<PromotionEntity> findByTypeAndEnabledTrueAndStartAtBeforeAndEndAtAfterOrderByEndAtAsc(
            String type, Instant startBefore, Instant endAfter);

}
