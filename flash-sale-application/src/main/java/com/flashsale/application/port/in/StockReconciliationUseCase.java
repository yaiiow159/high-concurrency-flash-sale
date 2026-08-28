package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ActivityReconciliation;

import java.util.List;

/**
 * 庫存對帳入站埠。
 *
 * <p><b>為什麼最終一致的系統一定要有對帳？</b>
 * 因為它沒有資料庫交易兜底。Redis 餘量與訂單資料分屬兩個系統，
 * 任何一次補償失敗、訊息遺失、或人為誤操作造成的偏差都<b>不會自癒</b>，
 * 只會日積月累。
 *
 * <p>{@code seckill.compensation.total} 這類指標只能捕捉到「我們知道自己失敗了」的情況；
 * 真正危險的是那些沒有拋出任何例外、卻已經對不上的偏差——只有主動核對才找得到。
 */
public interface StockReconciliationUseCase {

    /**
     * 對所有需要核對的活動執行一輪對帳。
     *
     * @return 每個活動的對帳結果，包含帳平的活動（供觀測趨勢，不只在出事時才有資料）
     */
    List<ActivityReconciliation> reconcileAll();

    /** 對單一活動執行對帳，供維運手動觸發與排查使用。 */
    ActivityReconciliation reconcile(Long activityId);
}
