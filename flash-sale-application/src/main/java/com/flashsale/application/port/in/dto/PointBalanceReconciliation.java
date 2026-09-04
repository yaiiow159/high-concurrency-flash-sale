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
public record PointBalanceReconciliation(int driftCount, List<Drift> drifts, boolean balanced) {

    /**
     * @param ledgerSum  流水的 delta 加總（真實來源）
     * @param balance    帳戶上的快照
     * @param difference 餘額減流水。正數代表餘額多了，負數代表流水多了——
     *                   兩個方向的成因不同，因此要看得出來是哪一種
     */
    public record Drift(Long userId, long ledgerSum, long balance, long difference) {
    }

    /**
     * {@code balanced} 是<b>真正的欄位</b>而不是導出方法。
     *
     * <p>導出方法不會被 Jackson 序列化進 record 的 JSON——呼叫端拿不到它，
     * 而文件上又寫著有。這個工廠方法保證它與明細一致，
     * 與 {@code SearchIndexReconciliation} 的做法一致。
     */
    public static PointBalanceReconciliation of(List<Drift> drifts) {
        return new PointBalanceReconciliation(drifts.size(), drifts, drifts.isEmpty());
    }
}
