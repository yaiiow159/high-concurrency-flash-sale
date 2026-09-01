package com.flashsale.application.port.out;

import com.flashsale.domain.inventory.Inventory;
import com.flashsale.domain.inventory.InventoryMovement;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SKU 庫存持久化埠（出站）。
 *
 * <p>這個埠服務的是<b>低頻</b>操作：劃撥、釋放、對帳、後台調整。
 * 一般下單的高頻扣減不走這裡——它走 {@link InventoryService}，
 * 由基礎設施層以單一條件式 UPDATE 完成，避免「讀出來、改、寫回去」
 * 這種在併發下必須靠重試才正確的往返。
 */
public interface InventoryRepository {

    Optional<Inventory> findBySkuId(Long skuId);

    List<Inventory> findBySkuIds(List<Long> skuIds);

    /**
     * 以樂觀鎖寫回。
     *
     * @return {@code true} 表示版本相符、寫入成功；{@code false} 表示期間有人改過，
     *         呼叫端應重讀後重試。<b>回傳布林而非拋例外</b>，因為版本衝突在
     *         併發下是預期會發生的正常狀況，不是異常
     */
    boolean save(Inventory inventory);

    /** 建立新的庫存紀錄；已存在時不覆蓋（避免把已賣出的量重設回去）。 */
    void createIfAbsent(Long skuId, int initialQuantity);

    /**
     * 記錄異動流水。
     *
     * <p>{@code refType + refNo + type} 有唯一索引，重複寫入會被資料庫擋下。
     *
     * @return {@code true} 表示這是新流水；{@code false} 表示先前已記錄過
     */
    boolean recordMovement(InventoryMovement movement);

    /** 查詢某活動當初劃撥出去的量，供釋放時計算未售量。 */
    Optional<Integer> findAllocatedQuantity(Long activityId, Long skuId);

    /**
     * 此活動的庫存是否已經釋放回可售池。
     *
     * <p>用來擋住「釋放後又重新預熱」：劃撥流水的唯一索引會讓重新劃撥被略過，
     * 但 Redis 的初始化不受它管——結果就是 Redis 有一批可賣的量，
     * 而 MySQL 的可售量從沒為它付過帳。那正是雙模型最該避免的超賣形狀。
     */
    boolean isReleased(Long activityId, Long skuId);

    /** 所有有庫存紀錄的 SKU，供對帳分批掃描。 */
    List<Long> findSkuIdsForReconciliation(int limit, int offset);

    /**
     * 批次彙總流水，供對帳核對「數字」與「流水」是否一致。
     *
     * <p><b>刻意做成批次而非逐筆。</b>對帳要掃過所有 SKU，
     * 一個 SKU 一次查詢在數萬個 SKU 下就是數萬次往返——
     * 對帳排程自己會變成故障源，而它本該是偵測故障的那個。
     *
     * @return SKU ID 對應到流水淨額；完全沒有流水的 SKU 不會出現在結果裡
     */
    Map<Long, LedgerTotals> sumLedgerBySkuIds(List<Long> skuIds);

    /** 某個 SKU 所有流水的淨額。 */
    record LedgerTotals(long availableDelta, long allocatedDelta) {

        public static final LedgerTotals EMPTY = new LedgerTotals(0, 0);
    }
}
