package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.PromotionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PromotionJpaRepository extends JpaRepository<PromotionEntity, Long> {

    /**
     * 目前生效中、且不需要券的優惠。
     *
     * <p>排除 {@code COUPON}：券的規則雖然也存在 promotion 表，
     * 但它只有在使用者主動出示券時才適用。混在這裡回傳的話，
     * 每個人都會自動享有所有券的折扣。
     */
    @Query("""
            select p from PromotionEntity p
             where p.enabled = true
               and p.type <> 'COUPON'
               and p.startAt <= :now
               and p.endAt > :now
             order by p.id
            """)
    List<PromotionEntity> findActive(@Param("now") Instant now);
}
