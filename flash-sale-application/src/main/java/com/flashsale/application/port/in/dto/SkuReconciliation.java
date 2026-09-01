package com.flashsale.application.port.in.dto;

import com.flashsale.domain.stock.ReconciliationVerdict;

/**
 * 單一 SKU 的庫存對帳結果。
 *
 * <p>核對的恆等式與秒殺那條不同：<b>這裡比對的是「數字」與「流水」。</b>
 *
 * <pre>
 *   available == Σ(所有流水的 availableDelta)
 *   allocated == Σ(所有流水的 allocatedDelta)
 * </pre>
 *
 * <p>換句話說：庫存欄位上的每一個數字，都必須有一連串異動紀錄能解釋它是怎麼來的。
 * 對不上就代表有人繞過了正規路徑改動庫存——直接改資料庫、漏寫流水的新程式碼、
 * 或某個交易只成功了一半。這三者在事發當下都看不出來，只有對帳會說話。
 *
 * <p>ADR-0008 明確要求雙模型上線時對帳必須同步擴充：
 * 一般庫存沒有 Redis 那種「扣減憑證」可查，
 * 流水就是它唯一的稽核來源；沒有對帳的雙模型比單模型更危險。
 *
 * @param availableDrift {@code available − Σ availableDelta}
 * @param allocatedDrift {@code allocated − Σ allocatedDelta}
 */
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
