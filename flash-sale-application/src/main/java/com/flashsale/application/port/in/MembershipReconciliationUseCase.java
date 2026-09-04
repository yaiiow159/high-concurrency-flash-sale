package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.PointBalanceReconciliation;

/**
 * 積分對帳：流水加總是否等於餘額。
 *
 * <p><b>只讀不修</b>——與一般庫存對帳（{@code InventoryReconciliationUseCase}）
 * 同一個立場：這裡的偏差本身就代表有東西繞過了正規路徑，
 * 此時「自動修正」等於用一個猜測覆蓋另一個猜測。
 */
public interface MembershipReconciliationUseCase {

    PointBalanceReconciliation reconcile();
}
