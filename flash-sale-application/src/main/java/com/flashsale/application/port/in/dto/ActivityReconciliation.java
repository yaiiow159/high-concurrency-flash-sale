package com.flashsale.application.port.in.dto;

import com.flashsale.domain.stock.ReconciliationVerdict;

/**
 * 單一活動的對帳結果。
 *
 * @param expectedAvailable 依訂單推算的應有餘量 = {@code totalStock - activeOrderQuantity}
 * @param drift             {@code actualAvailable - expectedAvailable}，正負方向決定判定
 * @param orphanBindings    已扣庫存但查無訂單、且已超過寬限期的紀錄數
 * @param repairedBindings  本次實際退回的孤兒扣減數（未啟用自動修復時恆為 0）
 * @param stockUnbacked     Redis 有庫存，但 MySQL 沒有對應的劃撥額度撐著。
 *                          這批貨沒有人付過帳，等於可以被賣兩次
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
        int repairedBindings,
        boolean stockUnbacked
) {

    public static ActivityReconciliation notInitialized(Long activityId, int totalStock) {
        return new ActivityReconciliation(activityId, totalStock, 0, -1, -1, 0,
                ReconciliationVerdict.NOT_INITIALIZED, 0, 0, false);
    }

    /**
     * @param stockUnbacked Redis 有這場活動的庫存，但 MySQL 那邊沒有對應的劃撥額度。
     *                      <b>一律判為超賣風險</b>，即使 Redis 餘量與訂單數完全對得上——
     *                      這兩件事檢查的不是同一回事：前者問「賣掉的有沒有記錄」，
     *                      後者問「這批貨到底是不是我們的」。
     *                      沒有劃撥撐著的庫存，一般通道也會把同一批貨賣一次
     */
    public static ActivityReconciliation of(Long activityId, int totalStock, long activeOrderQuantity,
                                            long actualAvailable, int orphanBindings,
                                            int repairedBindings, boolean stockUnbacked) {
        long expected = totalStock - activeOrderQuantity;
        long drift = actualAvailable - expected;
        ReconciliationVerdict verdict = stockUnbacked
                ? ReconciliationVerdict.OVERSELL_RISK
                : ReconciliationVerdict.fromDrift(drift);
        return new ActivityReconciliation(activityId, totalStock, activeOrderQuantity, actualAvailable,
                expected, drift, verdict, orphanBindings, repairedBindings, stockUnbacked);
    }

    /** 供日誌輸出的單行摘要。 */
    public String summary() {
        return "活動 %d [%s] 實際=%d 應有=%d 偏差=%+d 孤兒扣減=%d 已修復=%d%s"
                .formatted(activityId, verdict, actualAvailable, expectedAvailable,
                        drift, orphanBindings, repairedBindings,
                        stockUnbacked ? " 【庫存無劃撥支撐】" : "");
    }
}
