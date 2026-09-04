package com.flashsale.application.port.in.dto;

import java.util.List;

/**
 * 評分聚合對帳結果。
 *
 * <p><b>只列不平的商品</b>，帳平的不佔回應——與積分、庫存對帳同一個做法。
 *
 * @param driftCount 不平的商品數
 */
public record RatingReconciliation(int driftCount, List<Drift> drifts, boolean balanced) {

    /**
     * @param actualCount 從 {@code review} 表數出來的則數（真實來源）
     * @param actualSum   從 {@code review} 表加出來的評分總和
     * @param storedCount {@code product_rating} 上的快照
     * @param storedSum   同上
     */
    public record Drift(Long productId, long actualCount, long actualSum,
                        long storedCount, long storedSum) {

        /** 平均分差多少。這才是使用者看得到的東西——則數差一筆沒人會發現，平均差 0.5 分會。 */
        public double averageGap() {
            double actual = actualCount == 0 ? 0 : (double) actualSum / actualCount;
            double stored = storedCount == 0 ? 0 : (double) storedSum / storedCount;
            return Math.abs(actual - stored);
        }
    }

    /**
     * {@code balanced} 是<b>真正的欄位</b>而不是導出方法——
     * 導出方法不會被 Jackson 序列化進 record 的 JSON，呼叫端拿不到它。
     */
    public static RatingReconciliation of(List<Drift> drifts) {
        return new RatingReconciliation(drifts.size(), drifts, drifts.isEmpty());
    }
}
