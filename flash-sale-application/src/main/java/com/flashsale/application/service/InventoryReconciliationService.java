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

/**
 * 一般庫存對帳：核對「數字」與「流水」。
 *
 * <p>與秒殺對帳（{@link StockReconciliationService}）是兩條互補的防線，
 * 因為兩套庫存機制的失效方式完全不同：
 *
 * <table border="1">
 *   <caption>兩種對帳的差異</caption>
 *   <tr><th></th><th>秒殺</th><th>一般</th></tr>
 *   <tr><td>比對對象</td><td>Redis 餘量 vs 訂單數量</td><td>庫存欄位 vs 異動流水</td></tr>
 *   <tr><td>失效樣態</td><td>補償沒跑、訊息遺失</td><td>漏寫流水、繞過正規路徑改動</td></tr>
 *   <tr><td>可自動修</td><td>孤兒扣減（有條件）</td><td><b>一律不自動修</b></td></tr>
 * </table>
 *
 * <p><b>這裡完全不做自動修復</b>，比秒殺那邊更保守。理由是這裡的偏差本身就代表
 * 「有東西繞過了正規路徑」——既然連正規路徑都沒被遵守，
 * 對帳也就無從判斷哪一邊才是對的。此時自動「修正」等於用一個猜測覆蓋另一個猜測。
 *
 * <p>ADR-0008 把這件事列為 P1 不可延後的項目：
 * 一般庫存沒有 Redis 那種扣減憑證可查，流水是它唯一的稽核來源。
 * 沒有對帳的雙模型比單模型更危險——多了一套機制，卻少了看得見它的方法。
 */
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

    /**
     * 分批掃過所有 SKU。
     *
     * <p>分批的理由與秒殺對帳掃描綁定時相同：SKU 數量會成長到數萬，
     * 一次全撈進記憶體，對帳排程自己就會變成故障源。
     * 記憶體用量固定在單批大小，與商品規模無關。
     */
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

    /**
     * 對一批 SKU 對帳，<b>回傳全部結果而非只回不平的</b>。
     *
     * <p>過濾放在呼叫端：全量掃描只在意不平的，單筆查詢卻必須看到實際數字。
     * 若在這裡就濾掉帳平的，單筆查詢會拿不到結果而退回「查無庫存」的預設值——
     * 維運看到的就是一個帳平 SKU 顯示成 available=0，
     * 那比沒有這個端點更糟：它會讓人以為庫存消失了。
     */
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
