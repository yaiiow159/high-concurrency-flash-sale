package com.flashsale.application.port.out;

import com.flashsale.application.port.out.InventoryMetrics;
import com.flashsale.application.port.in.dto.SkuReconciliation;

/** 一般庫存的業務指標。 */
public interface InventoryMetrics {

    void recordSkuReconciliation(SkuReconciliation result);

    /** @param action {@code allocate} 或 {@code release} */
    void recordAllocation(String action, boolean applied);
}
