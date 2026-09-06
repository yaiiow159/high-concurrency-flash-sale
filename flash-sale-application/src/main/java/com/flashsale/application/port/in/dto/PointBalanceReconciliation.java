package com.flashsale.application.port.in.dto;

import java.util.List;

/** 積分對帳結果。 */
public record PointBalanceReconciliation(int driftCount, List<Drift> drifts, boolean balanced) {

    /**
     * @param ledgerSum  流水的 delta 加總（真實來源）
     * @param balance    帳戶上的快照
     * @param difference 餘額減流水。正數代表餘額多了，負數代表流水多了——
     * 兩個方向的成因不同，因此要看得出來是哪一種
     */
    public record Drift(Long userId, long ledgerSum, long balance, long difference) {
    }

    /** {@code balanced} 是<b>真正的欄位</b>而不是導出方法。 */
    public static PointBalanceReconciliation of(List<Drift> drifts) {
        return new PointBalanceReconciliation(drifts.size(), drifts, drifts.isEmpty());
    }
}
