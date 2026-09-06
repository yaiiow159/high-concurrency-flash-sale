package com.flashsale.application.port.in.dto;

import java.util.List;

/** 搜尋索引對帳結果（ADR-0012 的「要付出的成本」那一條）。 */
public record SearchIndexReconciliation(
        long indexedCount,
        long onShelfCount,
        List<Long> missing,
        List<Long> orphaned,
        long repaired,
        boolean balanced
) {

    /** 回報的 ID 上限。 */
    public static final int MAX_REPORTED = 100;

    public static SearchIndexReconciliation of(long indexedCount, long onShelfCount,
                                               List<Long> missing, List<Long> orphaned,
                                               long repaired) {
        return new SearchIndexReconciliation(
                indexedCount, onShelfCount,
                missing.stream().limit(MAX_REPORTED).toList(),
                orphaned.stream().limit(MAX_REPORTED).toList(),
                repaired,
                missing.isEmpty() && orphaned.isEmpty());
    }
}
