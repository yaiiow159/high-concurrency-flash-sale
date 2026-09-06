package com.flashsale.application.port.in.dto;

import com.flashsale.domain.stock.ReconciliationVerdict;

/** 單一 SKU 的庫存對帳結果。 */
public record SkuReconciliation(
        Long skuId,
        int available,
        int allocated,
        long ledgerAvailable,
        long ledgerAllocated,
        long availableDrift,
        long allocatedDrift,
        ReconciliationVerdict verdict
) {

    public static SkuReconciliation of(Long skuId, int available, int allocated,
                                       long ledgerAvailable, long ledgerAllocated) {
        long availableDrift = available - ledgerAvailable;
        long allocatedDrift = allocated - ledgerAllocated;

        // 兩條恆等式只要有一條不成立，這個 SKU 的帳就是壞的。
        // 取偏差絕對值較大的那一條決定判定方向，讓告警指向影響較大的那一邊。
        long dominant = Math.abs(availableDrift) >= Math.abs(allocatedDrift)
                ? availableDrift : allocatedDrift;

        return new SkuReconciliation(skuId, available, allocated,
                ledgerAvailable, ledgerAllocated, availableDrift, allocatedDrift,
                ReconciliationVerdict.fromDrift(dominant));
    }

    public boolean isBalanced() {
        return availableDrift == 0 && allocatedDrift == 0;
    }

    /** 供日誌輸出的單行摘要。 */
    public String summary() {
        return "SKU %d [%s] 可售=%d(流水 %d, 偏差 %+d) 劃撥=%d(流水 %d, 偏差 %+d)"
                .formatted(skuId, verdict, available, ledgerAvailable, availableDrift,
                        allocated, ledgerAllocated, allocatedDrift);
    }
}
