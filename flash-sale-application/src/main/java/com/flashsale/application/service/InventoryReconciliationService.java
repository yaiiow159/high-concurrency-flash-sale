package com.flashsale.application.service;

import com.flashsale.application.config.ReconciliationPolicy;
import com.flashsale.application.port.in.InventoryReconciliationUseCase;
import com.flashsale.application.port.in.dto.SkuReconciliation;
import com.flashsale.application.port.out.InventoryRepository;
import com.flashsale.domain.inventory.Inventory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 一般庫存對帳：核對「數字」與「流水」。 */
@Service
public class InventoryReconciliationService implements InventoryReconciliationUseCase {

    private static final Logger log = LoggerFactory.getLogger(InventoryReconciliationService.class);

    private final InventoryRepository inventoryRepository;
    private final InventoryMetrics metrics;
    private final ReconciliationPolicy policy;

    public InventoryReconciliationService(InventoryRepository inventoryRepository,
                                          InventoryMetrics metrics,
                                          ReconciliationPolicy policy) {
        this.inventoryRepository = inventoryRepository;
        this.metrics = metrics;
        this.policy = policy;
    }

    /** 分批掃過所有 SKU。 */
    @Override
    public List<SkuReconciliation> reconcileAll() {
        int batchSize = policy.scanBatchSize();
        List<SkuReconciliation> unbalanced = new ArrayList<>();
        int offset = 0;
        int scanned = 0;

        while (true) {
            List<Long> skuIds = inventoryRepository.findSkuIdsForReconciliation(batchSize, offset);
            if (skuIds.isEmpty()) {
                break;
            }
            scanned += skuIds.size();
            reconcileBatch(skuIds).stream()
                    .filter(result -> !result.isBalanced())
                    .forEach(unbalanced::add);

            if (skuIds.size() < batchSize) {
                break;
            }
            offset += batchSize;
        }

        // 帳平的結果不回傳也不記錄——數萬筆「一切正常」會把真正的異常埋掉。
        // 趨勢觀測交給指標，回傳值只留給需要人看的事。
        log.info("一般庫存對帳完成：掃描 {} 個 SKU，{} 個不平", scanned, unbalanced.size());
        return unbalanced;
    }

    @Override
    public SkuReconciliation reconcile(Long skuId) {
        return reconcileBatch(List.of(skuId)).stream().findFirst()
                .orElseGet(() -> noInventoryFor(skuId));
    }

    /** 對一批 SKU 對帳，<b>回傳全部結果而非只回不平的</b>。 */
    private List<SkuReconciliation> reconcileBatch(List<Long> skuIds) {
        List<Inventory> inventories = inventoryRepository.findBySkuIds(skuIds);
        Map<Long, InventoryRepository.LedgerTotals> ledger =
                inventoryRepository.sumLedgerBySkuIds(skuIds);

        List<SkuReconciliation> results = new ArrayList<>(inventories.size());
        for (Inventory inventory : inventories) {
            // 查無流水視為全零，而不是跳過：一個有庫存數字卻完全沒有流水的 SKU
            // 正是最該被報出來的情況——那批貨是憑空出現的。
            InventoryRepository.LedgerTotals totals =
                    ledger.getOrDefault(inventory.skuId(), InventoryRepository.LedgerTotals.EMPTY);

            SkuReconciliation result = SkuReconciliation.of(
                    inventory.skuId(), inventory.available(), inventory.allocated(),
                    totals.availableDelta(), totals.allocatedDelta());

            metrics.recordSkuReconciliation(result);
            if (!result.isBalanced()) {
                log.error("一般庫存對帳不平：{}", result.summary());
            }
            results.add(result);
        }
        return results;
    }

    private SkuReconciliation noInventoryFor(Long skuId) {
        // 只有在這個 SKU 根本沒有庫存紀錄時才會走到這裡。
        // 回全零而非拋例外——查詢一個尚未建帳的 SKU 是合理的操作。
        return SkuReconciliation.of(skuId, 0, 0, 0, 0);
    }
}
