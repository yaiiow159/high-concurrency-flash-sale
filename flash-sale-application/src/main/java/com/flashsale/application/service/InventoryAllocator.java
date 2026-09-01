package com.flashsale.application.service;

import com.flashsale.application.port.out.InventoryRepository;
import com.flashsale.domain.inventory.Inventory;
import com.flashsale.domain.inventory.InventoryMovement;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 劃撥與釋放的交易單元。
 *
 * <p><b>刻意獨立成一個 Bean。</b>Spring 的交易是動態代理，
 * 同一個 Bean 內部呼叫 {@code this.method()} 不會經過代理，
 * {@code @Transactional} 會安靜失效且沒有任何錯誤訊息。
 * {@link StockAllocationService} 需要在交易外做 Redis 操作、在交易內改資料庫，
 * 兩者若放在同一個類別，交易邊界就會消失。
 * （同樣的拆分見 {@code OutboxRelayScheduler} 與 {@code OutboxRelayer}。）
 *
 * <p>庫存數字的變更與流水必須在同一個交易裡：
 * 流水寫成功但庫存沒改，會讓後續對帳把一筆不存在的異動當成真的。
 */
@Service
public class InventoryAllocator {

    private static final Logger log = LoggerFactory.getLogger(InventoryAllocator.class);

    /**
     * 樂觀鎖重試次數。
     *
     * <p>劃撥是低頻操作且外層有分散式鎖，真的撞版本的機率極低。
     * 給 3 次是為了容忍「後台正好在調整同一個 SKU」這種偶發交錯，
     * 而不是把重試當成併發控制手段——那是高頻扣減才需要煩惱的事。
     */
    private static final int MAX_RETRIES = 3;

    private final InventoryRepository inventoryRepository;
    private final InventoryMetrics metrics;

    public InventoryAllocator(InventoryRepository inventoryRepository,
                              InventoryMetrics metrics) {
        this.inventoryRepository = inventoryRepository;
        this.metrics = metrics;
    }

    /**
     * 把 {@code quantity} 件從可售池劃撥給活動。
     *
     * @return {@code false} 表示這場活動先前已劃撥過，本次不重複執行
     */
    @Transactional
    public boolean allocate(Long activityId, Long skuId, int quantity, Instant now) {
        // 流水的唯一索引就是冪等閘門：重覆預熱、多台機器同時啟動都只會成功一次。
        // 先寫流水再改數字，是因為流水失敗代表「已經做過」，此時不該再動庫存。
        if (!inventoryRepository.recordMovement(
                InventoryMovement.allocate(skuId, quantity, activityId, now))) {
            log.debug("活動 {} 的庫存已劃撥過，略過", activityId);
            metrics.recordAllocation("allocate", false);
            return false;
        }
        mutate(skuId, inventory -> inventory.allocate(quantity));
        metrics.recordAllocation("allocate", true);
        log.info("活動 {} 劃撥完成：SKU {} 切出 {} 件", activityId, skuId, quantity);
        return true;
    }

    /**
     * 活動結束後歸還未售出的部分。
     *
     * @param allocatedQuantity 當初劃撥的量
     * @param unsoldQuantity    Redis 剩餘量，會回到可售池
     * @return {@code false} 表示先前已釋放過
     */
    @Transactional
    public boolean release(Long activityId, Long skuId, int allocatedQuantity,
                           int unsoldQuantity, Instant now) {
        if (!inventoryRepository.recordMovement(InventoryMovement.release(
                skuId, allocatedQuantity, unsoldQuantity, activityId, now))) {
            log.debug("活動 {} 的庫存已釋放過，略過", activityId);
            metrics.recordAllocation("release", false);
            return false;
        }
        mutate(skuId, inventory -> inventory.release(allocatedQuantity, unsoldQuantity));
        metrics.recordAllocation("release", true);
        log.info("活動 {} 釋放完成：SKU {} 收回 {} 件（劃撥 {}，售出 {}）",
                activityId, skuId, unsoldQuantity, allocatedQuantity,
                allocatedQuantity - unsoldQuantity);
        return true;
    }

    /**
     * 讀出、套用變更、以樂觀鎖寫回；版本衝突時重讀重試。
     *
     * <p>領域物件的不變式（可售量不足不得劃撥）在 {@code change} 裡被檢查，
     * 每次重試都會用<b>最新的</b>數字重新檢查一遍——
     * 這正是不能把檢查結果快取起來的理由。
     */
    private void mutate(Long skuId, java.util.function.Consumer<Inventory> change) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Inventory inventory = inventoryRepository.findBySkuId(skuId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVENTORY_NOT_FOUND,
                            "SKU %d 尚未建立庫存".formatted(skuId)));
            change.accept(inventory);
            if (inventoryRepository.save(inventory)) {
                return;
            }
            log.warn("SKU {} 樂觀鎖衝突，第 {}/{} 次重試", skuId, attempt, MAX_RETRIES);
        }
        // 低頻操作連撞三次版本，代表有非預期的併發來源，不該靜默放過
        throw new BusinessException(ErrorCode.STOCK_SERVICE_UNAVAILABLE,
                "SKU %d 庫存更新持續衝突，請稍後再試".formatted(skuId));
    }
}
