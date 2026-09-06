package com.flashsale.application.port.out;

import com.flashsale.domain.inventory.Inventory;
import com.flashsale.domain.inventory.InventoryMovement;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** SKU 庫存持久化埠（出站）。 */
public interface InventoryRepository {

    Optional<Inventory> findBySkuId(Long skuId);

    List<Inventory> findBySkuIds(List<Long> skuIds);

    /**
     * 以樂觀鎖寫回。
     *
     * @return {@code true} 表示版本相符、寫入成功；{@code false} 表示期間有人改過，
     * 呼叫端應重讀後重試。<b>回傳布林而非拋例外</b>，因為版本衝突在
     * 併發下是預期會發生的正常狀況，不是異常
     */
    boolean save(Inventory inventory);

    /** 建立新的庫存紀錄；已存在時不覆蓋（避免把已賣出的量重設回去）。 */
    void createIfAbsent(Long skuId, int initialQuantity);

    /** 記錄異動流水。 */
    boolean recordMovement(InventoryMovement movement);

    /** 查詢某活動當初劃撥出去的量，供釋放時計算未售量。 */
    Optional<Integer> findAllocatedQuantity(Long activityId, Long skuId);

    /** 此活動的庫存是否已經釋放回可售池。 */
    boolean isReleased(Long activityId, Long skuId);

    /** 所有有庫存紀錄的 SKU，供對帳分批掃描。 */
    List<Long> findSkuIdsForReconciliation(int limit, int offset);

    /** 批次彙總流水，供對帳核對「數字」與「流水」是否一致。 */
    Map<Long, LedgerTotals> sumLedgerBySkuIds(List<Long> skuIds);

    /** 某個 SKU 所有流水的淨額。 */
    record LedgerTotals(long availableDelta, long allocatedDelta) {

        public static final LedgerTotals EMPTY = new LedgerTotals(0, 0);
    }
}
