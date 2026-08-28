package com.flashsale.application.port.in.dto;

import com.flashsale.domain.stock.ReconciliationVerdict;

/**
 * 單一活動的對帳結果。
 *
 * @param expectedAvailable 依訂單推算的應有餘量 = {@code totalStock - activeOrderQuantity}
 * @param drift             {@code actualAvailable - expectedAvailable}，正負方向決定判定
 * @param orphanBindings    已扣庫存但查無訂單、且已超過寬限期的紀錄數
 * @param repairedBindings  本次實際退回的孤兒扣減數（未啟用自動修復時恆為 0）
 */
public record ActivityReconciliation(
        Long activityId,
        int totalStock,
        long activeOrderQuantity,
        long actualAvailable,
        long expectedAvailable,
        long drift,
        ReconciliationVerdict verdict,
        int orphanBindings,
        int repairedBindings
) {

    public static ActivityReconciliation notInitialized(Long activityId, int totalStock) {
        return new ActivityReconciliation(activityId, totalStock, 0, -1, -1, 0,
                ReconciliationVerdict.NOT_INITIALIZED, 0, 0);
    }

    public static ActivityReconciliation of(Long activityId, int totalStock, long activeOrderQuantity,
                                            long actualAvailable, int orphanBindings, int repairedBindings) {
        long expected = totalStock - activeOrderQuantity;
        long drift = actualAvailable - expected;
        return new ActivityReconciliation(activityId, totalStock, activeOrderQuantity, actualAvailable,
                expected, drift, ReconciliationVerdict.fromDrift(drift), orphanBindings, repairedBindings);
    }

    /** 供日誌輸出的單行摘要。 */
    public String summary() {
        return "活動 %d [%s] 實際=%d 應有=%d 偏差=%+d 孤兒扣減=%d 已修復=%d"
                .formatted(activityId, verdict, actualAvailable, expectedAvailable,
                        drift, orphanBindings, repairedBindings);
    }
}
