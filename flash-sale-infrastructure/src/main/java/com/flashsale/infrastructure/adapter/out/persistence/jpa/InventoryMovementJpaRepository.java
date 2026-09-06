package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.InventoryMovementEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InventoryMovementJpaRepository
        extends JpaRepository<InventoryMovementEntity, Long> {

    boolean existsByRefTypeAndRefNoAndTypeAndSkuId(String refType, String refNo,
                                                  String type, Long skuId);

    /** 劃撥流水的 allocatedDelta 為正，即當初切出去的量。 */
    @Query("""
            select m.allocatedDelta from InventoryMovementEntity m
             where m.refType = 'ACTIVITY' and m.refNo = :activityId
               and m.skuId = :skuId and m.type = 'ALLOCATE'
            """)
    Optional<Integer> findAllocatedQuantity(@Param("activityId") String activityId,
                                            @Param("skuId") Long skuId);

    /** 批次彙總多個 SKU 的流水淨額，供對帳核對「現在的數字」與「所有異動的總和」。 */
    @Query("""
            select m.skuId, sum(m.availableDelta), sum(m.allocatedDelta)
              from InventoryMovementEntity m
             where m.skuId in :skuIds
             group by m.skuId
            """)
    java.util.List<Object[]> sumDeltasBySkuIds(@Param("skuIds") java.util.List<Long> skuIds);
}
