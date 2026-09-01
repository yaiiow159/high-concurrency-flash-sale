package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.InventoryRepository;
import com.flashsale.domain.inventory.Inventory;
import com.flashsale.domain.inventory.InventoryMovement;
import com.flashsale.domain.inventory.InventoryMovementType;
import com.flashsale.infrastructure.adapter.out.persistence.entity.InventoryEntity;
import com.flashsale.infrastructure.adapter.out.persistence.entity.InventoryMovementEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.InventoryJpaRepository;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.InventoryMovementJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** 庫存持久化埠的 JPA 實作。 */
@Repository
public class JpaInventoryRepository implements InventoryRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaInventoryRepository.class);

    private final InventoryJpaRepository inventoryJpaRepository;
    private final InventoryMovementJpaRepository movementJpaRepository;
    private final Clock clock;

    public JpaInventoryRepository(InventoryJpaRepository inventoryJpaRepository,
                                  InventoryMovementJpaRepository movementJpaRepository,
                                  Clock clock) {
        this.inventoryJpaRepository = inventoryJpaRepository;
        this.movementJpaRepository = movementJpaRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Inventory> findBySkuId(Long skuId) {
        return inventoryJpaRepository.findById(skuId).map(JpaInventoryRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Inventory> findBySkuIds(List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            // 空集合會產生 `in ()` 這種在部分資料庫上非法的 SQL
            return List.of();
        }
        return inventoryJpaRepository.findAllById(skuIds).stream()
                .map(JpaInventoryRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public boolean save(Inventory inventory) {
        try {
            InventoryEntity entity = inventoryJpaRepository.findById(inventory.skuId())
                    .orElseThrow(() -> new IllegalStateException(
                            "寫回時找不到 SKU " + inventory.skuId() + " 的庫存"));
            entity.applyChanges(inventory.available(), inventory.allocated(), clock.instant());
            inventoryJpaRepository.saveAndFlush(entity);
            return true;
        } catch (ObjectOptimisticLockingFailureException e) {
            // 版本衝突在併發下是預期狀況，不是異常——交給呼叫端重讀重試
            log.debug("SKU {} 樂觀鎖衝突", inventory.skuId());
            return false;
        }
    }

    @Override
    @Transactional
    public void createIfAbsent(Long skuId, int initialQuantity) {
        if (inventoryJpaRepository.existsById(skuId)) {
            // 不覆蓋既有紀錄：那會把已經賣出的量重新加回去
            return;
        }
        inventoryJpaRepository.save(
                new InventoryEntity(skuId, initialQuantity, 0, clock.instant()));
    }

    @Override
    @Transactional
    public boolean recordMovement(InventoryMovement movement) {
        // 先查一次是為了避開常見情況下的例外成本；真正的裁決者是下面的唯一索引。
        if (movementJpaRepository.existsByRefTypeAndRefNoAndTypeAndSkuId(
                movement.refType(), movement.refNo(),
                movement.type().name(), movement.skuId())) {
            return false;
        }
        try {
            movementJpaRepository.saveAndFlush(new InventoryMovementEntity(
                    movement.skuId(), movement.type().name(),
                    movement.availableDelta(), movement.allocatedDelta(),
                    movement.refType(), movement.refNo(), movement.occurredAt()));
            return true;
        } catch (DataIntegrityViolationException e) {
            // 兩台機器同時通過上面的存在性檢查時走到這裡。
            // 這不是錯誤，正是唯一索引該發揮作用的時刻。
            log.debug("流水已由其他節點寫入 refType={}, refNo={}, type={}",
                    movement.refType(), movement.refNo(), movement.type());
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Integer> findAllocatedQuantity(Long activityId, Long skuId) {
        return movementJpaRepository.findAllocatedQuantity(String.valueOf(activityId), skuId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isReleased(Long activityId, Long skuId) {
        return movementJpaRepository.existsByRefTypeAndRefNoAndTypeAndSkuId(
                InventoryMovement.RefType.ACTIVITY, String.valueOf(activityId),
                InventoryMovementType.RELEASE.name(), skuId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findSkuIdsForReconciliation(int limit, int offset) {
        return inventoryJpaRepository.findAllSkuIds(
                PageRequest.of(offset / Math.max(limit, 1), limit));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, LedgerTotals> sumLedgerBySkuIds(List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        return movementJpaRepository.sumDeltasBySkuIds(skuIds).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> new LedgerTotals(
                                ((Number) row[1]).longValue(),
                                ((Number) row[2]).longValue())));
    }

    private static Inventory toDomain(InventoryEntity entity) {
        return Inventory.restore(entity.getSkuId(), entity.getAvailable(),
                entity.getAllocated(), entity.getVersion());
    }
}
