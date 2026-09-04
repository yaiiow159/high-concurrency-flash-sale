package com.flashsale.application.port.in.dto;

import java.util.List;

/**
 * 積分對帳結果。
 *
 * <p><b>只列不平的帳戶</b>，帳平的不佔回應——與全量庫存對帳同一個做法。
 * 回一整份「全部正常」的清單，會讓真正的問題埋在幾萬列裡。
 *
 * @param driftCount 不平的帳戶數
 */
public record PointBalanceReconciliation(int driftCount, List<Drift> drifts) {

    /**
     * @param ledgerSum  流水的 delta 加總（真實來源）
     * @param balance    帳戶上的快照
     * @param difference 餘額減流水。正數代表餘額多了，負數代表流水多了——
     *                   兩個方向的成因不同，因此要看得出來是哪一種
     */
    public record Drift(Long userId, long ledgerSum, long balance, long difference) {
    }

    public static PointBalanceReconciliation of(List<Drift> drifts) {
        return new PointBalanceReconciliation(drifts.size(), drifts);
    }

    /** 由明細是否為空推導，不另存一個布林——兩個欄位描述同一件事遲早會不一致。 */
    public boolean balanced() {
        return drifts.isEmpty();
    }
}
