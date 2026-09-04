package com.flashsale.application.port.in.dto;

import java.util.List;

/**
 * 搜尋索引對帳結果（ADR-0012 的「要付出的成本」那一條）。
 *
 * <p>核對的恆等式很簡單：
 *
 * <pre>
 *   索引裡的文件 ID 集合 == 資料庫裡 ON_SHELF 的商品 ID 集合
 * </pre>
 *
 * <p>它必須存在，是因為<b>分岔完全靜默</b>。索引落後時搜尋照樣回結果、
 * 照樣 HTTP 200，只是結果是錯的：下架的商品繼續被搜到（點進去買不到），
 * 上架的商品永遠搜不到（賣不出去，而沒有人會抱怨一個他不知道存在的商品）。
 * 事件漏掉或消費失敗都會走到這裡，而兩者都不會有任何錯誤浮到使用者面前。
 *
 * @param missing  在資料庫是 ON_SHELF、卻不在索引裡。症狀是「商品搜不到」
 * @param orphaned 在索引裡、卻不是 ON_SHELF。症狀是「搜到了但買不到」
 */
public record SearchIndexReconciliation(
        long indexedCount,
        long onShelfCount,
        List<Long> missing,
        List<Long> orphaned,
        long repaired,
        boolean balanced
) {

    /**
     * 回報的 ID 上限。
     *
     * <p>索引整份掉了的時候差異會是全部商品，把幾萬個 ID 塞進回應
     * 只會讓人看不到重點——而那個情境要做的是重建索引，不是逐筆看。
     */
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
