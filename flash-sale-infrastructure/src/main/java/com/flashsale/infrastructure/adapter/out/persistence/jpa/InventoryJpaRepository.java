package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.InventoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface InventoryJpaRepository extends JpaRepository<InventoryEntity, Long> {

    /** 一般下單的高頻扣減：單一條件式 UPDATE，不做「讀出來、改、寫回去」。 */
    @Modifying
    @Query("""
            update InventoryEntity i
               set i.available = i.available - :quantity,
                   i.version = i.version + 1,
                   i.updatedAt = :now
             where i.skuId = :skuId
               and i.available >= :quantity
            """)
    int deductAvailable(@Param("skuId") Long skuId,
                        @Param("quantity") int quantity,
                        @Param("now") Instant now);

    /** 退回可售量。無條件成立，因此不需要檢查回傳列數以外的東西。 */
    @Modifying
    @Query("""
            update InventoryEntity i
               set i.available = i.available + :quantity,
                   i.version = i.version + 1,
                   i.updatedAt = :now
             where i.skuId = :skuId
            """)
    int restoreAvailable(@Param("skuId") Long skuId,
                         @Param("quantity") int quantity,
                         @Param("now") Instant now);

    @Query("select i.skuId from InventoryEntity i order by i.skuId")
    List<Long> findAllSkuIds(org.springframework.data.domain.Pageable pageable);
}
