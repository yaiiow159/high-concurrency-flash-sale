package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ActivityReconciliation;

import java.util.List;

/** 庫存對帳入站埠。 */
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
