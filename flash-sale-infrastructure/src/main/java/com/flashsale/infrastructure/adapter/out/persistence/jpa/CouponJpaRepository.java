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

    /**
     * 核銷一張券（ADR-0013 決策 6）。
     *
     * <p><b>{@code status = 'ISSUED'} 這個條件是整個併發安全的所在。</b>
     * 檢查與寫入在資料庫的同一個語句內，兩個併發請求只有一個能讓受影響列數為 1。
     * 若改成「先查狀態、再更新」，兩邊都會讀到 ISSUED，同一張券就用了兩次——
     * 與退貨額度那次是同一類 read-modify-write，只是那裡臨界區跨多個語句
     * 只能用悲觀鎖，這裡可以壓成一句就不該加鎖。
     *
     * <p>過期券也一併擋在這裡：狀態還是 ISSUED 但已過期的券在批次任務跑之前
     * 依然存在，只靠狀態判斷會讓它能用。
     *
     * @return 受影響列數；{@code 0} 表示券已被使用或已過期，核銷未發生
     */
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
