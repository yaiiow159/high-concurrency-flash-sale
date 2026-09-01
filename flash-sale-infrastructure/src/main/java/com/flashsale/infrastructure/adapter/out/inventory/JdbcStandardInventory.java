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

/**
 * 一般商品的庫存機制：MySQL 行 + 條件式 UPDATE。
 *
 * <p>適用前提是<b>衝突率低</b>：數萬個 SKU 各自獨立，多數一天只賣出個位數，
 * 兩個請求同時扣同一個 SKU 的機率極小。此時資料庫完全夠用，
 * 而且天然有交易保證——扣庫存與建訂單可以在同一個交易裡，不需要補償。
 *
 * <p><b>這套機制絕不能用在秒殺上。</b>秒殺的所有請求競爭同一行，
 * 行鎖會讓它們排成一列，吞吐直接塌陷（ADR-0002 有完整論證）。
 * 這也是為什麼要有雙模型，而不是統一成一種。
 *
 * <p>原子性來自 {@code UPDATE ... WHERE available >= ?} 這個單一語句——
 * 檢查與扣減在資料庫內同時發生。任何「先 SELECT 再 UPDATE」的寫法都會超賣，
 * 這與 Redis 那邊必須用 Lua 是完全相同的道理，只是換了個資料庫。
 */
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

    /**
     * 扣減可售量。
     *
     * <p><b>三個步驟的順序是刻意的，而且不能對調：</b>
     *
     * <ol>
     *   <li>先查流水判重——重送同一張訂單直接回傳冪等結果，不碰庫存</li>
     *   <li>條件式 UPDATE 扣減——扣不動代表售罄，此時<b>什麼都還沒寫</b>，
     *       可以安全地回傳結果而不需要回滾</li>
     *   <li>最後才寫流水——只有真的扣成功才留下紀錄</li>
     * </ol>
     *
     * <p>「售罄」是預期中的正常結果，**絕不能用拋例外表達**：
     * 這個方法會被納入下單的交易，一旦拋出未受檢例外，
     * 整個交易會被標記成 rollback-only，就算上層把例外捕捉掉也救不回來，
     * 最後會在 commit 時炸成 {@code UnexpectedRollbackException}。
     * 那時錯誤訊息指向的地方與真正的原因隔著好幾層，極難追。
     */
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
        // 退庫的判重與寫入合併在 recordMovement 裡：
        // 它回 false 就代表退過了，此時不該再加回可售量。
        if (!inventoryRepository.recordMovement(InventoryMovement.restore(
                command.skuId(), command.quantity(), command.orderNo(), clock.instant()))) {
            log.debug("訂單 {} 的庫存已退回過，略過", command.orderNo());
            return false;
        }
        inventoryJpaRepository.restoreAvailable(
                command.skuId(), command.quantity(), clock.instant());
        return true;
    }
}
