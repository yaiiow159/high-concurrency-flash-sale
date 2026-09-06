package com.flashsale.infrastructure.adapter.out.inventory;

import com.flashsale.application.port.out.InventoryRepository;
import com.flashsale.application.port.out.InventoryService;
import com.flashsale.domain.inventory.InventoryMovement;
import com.flashsale.domain.inventory.InventoryMovementType;
import com.flashsale.domain.stock.StockDeductionOutcome;
import com.flashsale.domain.stock.StockDeductionResult;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.InventoryJpaRepository;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.InventoryMovementJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** 一般商品的庫存機制：MySQL 行 + 條件式 UPDATE。 */
@Component
public class JdbcStandardInventory implements InventoryService {

    private static final Logger log = LoggerFactory.getLogger(JdbcStandardInventory.class);

    private final InventoryJpaRepository inventoryJpaRepository;
    private final InventoryMovementJpaRepository movementJpaRepository;
    private final InventoryRepository inventoryRepository;
    private final Clock clock;

    public JdbcStandardInventory(InventoryJpaRepository inventoryJpaRepository,
                                 InventoryMovementJpaRepository movementJpaRepository,
                                 InventoryRepository inventoryRepository,
                                 Clock clock) {
        this.inventoryJpaRepository = inventoryJpaRepository;
        this.movementJpaRepository = movementJpaRepository;
        this.inventoryRepository = inventoryRepository;
        this.clock = clock;
    }

    /** 扣減可售量。 */
    @Override
    @Transactional
    public StockDeductionResult deduct(DeductCommand command) {
        if (movementJpaRepository.existsByRefTypeAndRefNoAndTypeAndSkuId(
                InventoryMovement.RefType.ORDER, command.orderNo(),
                InventoryMovementType.DEDUCT.name(), command.skuId())) {
            return StockDeductionResult.duplicate(command.orderNo());
        }

        int updated = inventoryJpaRepository.deductAvailable(
                command.skuId(), command.quantity(), clock.instant());
        if (updated == 0) {
            return StockDeductionResult.rejected(StockDeductionOutcome.SOLD_OUT);
        }

        if (!inventoryRepository.recordMovement(InventoryMovement.deduct(
                command.skuId(), command.quantity(), command.orderNo(), clock.instant()))) {
            // 走到這裡代表另一個節點在步驟 1 與 3 之間插了同一筆流水，
            // 也就是這張訂單被扣了兩次。這個交易必須整個回滾，
            // 讓多扣的那一次消失——這是少數「拋例外才正確」的情況。
            throw new IllegalStateException(
                    "訂單 %s 的庫存扣減發生競態，已回滾".formatted(command.orderNo()));
        }
        return StockDeductionResult.success(command.orderNo());
    }

    @Override
    @Transactional
    public boolean restore(RestoreCommand command) {
        // 退貨的來源記退貨單號而非訂單號——一張訂單可以有多張退貨單，
        // 都記訂單號的話第二張會被判定為重複而安靜略過（ADR-0011）
        InventoryMovement movement = command.returnNo() == null
                ? InventoryMovement.restore(command.skuId(), command.quantity(),
                        command.orderNo(), clock.instant())
                : InventoryMovement.restoreFromReturn(command.skuId(), command.quantity(),
                        command.returnNo(), clock.instant());

        // 退庫的判重與寫入合併在 recordMovement 裡：
        // 它回 false 就代表退過了，此時不該再加回可售量。
        if (!inventoryRepository.recordMovement(movement)) {
            log.debug("{} 的庫存已退回過，略過", movement.refNo());
            return false;
        }
        int updated = inventoryJpaRepository.restoreAvailable(
                command.skuId(), command.quantity(), clock.instant());
        if (updated == 0) {
            // 與 deduct 不同：扣不動是正常的業務結果（賣完了），
            // 退不動則一定是資料異常——SKU 沒有庫存列。
            // 不檢查的話流水記了 +q 而 available 沒動，兩邊從此對不上，
            // 而且完全無聲，要等對帳跑出來才會發現
            throw new IllegalStateException(
                    "退回庫存時找不到 SKU %d 的庫存列，流水已記但數量未變"
                            .formatted(command.skuId()));
        }
        return true;
    }
}
