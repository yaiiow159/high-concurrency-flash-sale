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

    /**
     * 開放用積分兌換的優惠。
     *
     * <p>{@code point_cost > 0} 而不是 {@code is not null}：
     * 兌換價填成 0 的資料不該出現在兌換清單上——免費的「兌換」多半是填錯，
     * 而它會讓所有人瞬間拿到無限張券。
     */
    @Query("""
            select p from PromotionEntity p
             where p.enabled = true
               and p.pointCost > 0
               and p.startAt <= :now
               and p.endAt > :now
             order by p.pointCost
            """)
    List<PromotionEntity> findExchangeable(@Param("now") Instant now);
    /**
     * 領券中心可以領的促銷。
     *
     * <p>與「進行中的促銷」分開：後者包含滿額折與免運，
     * 那些是下單時自動套用的，<b>不需要領</b>——混在一起會讓
     * 領券中心列出一堆按不下去的東西。
     *
     * <p>依結束時間排序：快到期的排前面，那是使用者最該先領的。
     */
    List<PromotionEntity> findByTypeAndEnabledTrueAndStartAtBeforeAndEndAtAfterOrderByEndAtAsc(
            String type, Instant startBefore, Instant endAfter);

}
