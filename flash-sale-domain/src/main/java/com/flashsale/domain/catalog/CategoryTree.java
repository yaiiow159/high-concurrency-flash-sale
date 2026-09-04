package com.flashsale.domain.catalog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 類目樹的查詢視角（ADR-0022）。
 *
 * <h2>為什麼在記憶體裡展開子孫，而不是遞迴 CTE 或物化路徑</h2>
 *
 * <p>這個決定與 {@code CategoryView.buildTree} 是同一個：
 * 類目總數以千為上限、幾乎不變、卻每次商品查詢都要用到。
 * 一次全撈再在記憶體裡走，比每次查詢多一段遞迴便宜得多。
 *
 * <p>物化路徑與閉包表都要在<b>寫入路徑</b>維護一個不變式，
 * 而那個不變式壞掉的症狀是「某些商品從此篩不到」——安靜、難查。
 * 在這個規模下不值得買這個風險。
 */
public final class CategoryTree {

    private final Map<Long, List<Long>> childIdsByParent;
    private final Set<Long> allIds;

    private CategoryTree(Map<Long, List<Long>> childIdsByParent, Set<Long> allIds) {
        this.childIdsByParent = childIdsByParent;
        this.allIds = allIds;
    }

    public static CategoryTree of(Collection<Category> categories) {
        Map<Long, List<Long>> byParent = new HashMap<>();
        Set<Long> ids = new HashSet<>();
        for (Category category : categories) {
            ids.add(category.id());
            if (!category.isRoot()) {
                byParent.computeIfAbsent(category.parentId(), key -> new ArrayList<>())
                        .add(category.id());
            }
        }
        return new CategoryTree(byParent, Set.copyOf(ids));
    }

    /**
     * 這個類目與它所有子孫的 ID。
     *
     * <p>用來把「點了某個類目」翻譯成「這些類目底下的商品」。
     * 少了它，點中間層的類目會得到空結果——商品只掛在葉節點上，
     * 而那正是真實商品目錄的樣子。
     *
     * <p>類目不存在時回傳只含它自己的集合，於是查詢結果為空。
     * 這比回傳「全部」安全：一個打錯的類目 ID 應該查不到東西，
     * 而不是安靜地變成「不篩選」。
     */
    public Set<Long> withDescendants(Long categoryId) {
        Set<Long> collected = new HashSet<>();
        Deque<Long> pending = new ArrayDeque<>();
        pending.push(categoryId);

        while (!pending.isEmpty()) {
            Long current = pending.pop();
            // 用 add 的回傳值擋住重複，順帶擋住環。
            // parent_id 沒有外鍵約束，資料壞掉時 A→B→A 是可能的，
            // 而那會讓遞迴永遠不結束——列表查詢就這樣把執行緒吃光
            if (!collected.add(current)) {
                continue;
            }
            childIdsByParent.getOrDefault(current, List.of()).forEach(pending::push);
        }
        return collected;
    }

    /**
     * 這組 ID 是否已經涵蓋整棵樹。
     *
     * <p>點根類目時展開出來的就是全部類目，此時再下
     * {@code category_id in (...)} 是一個沒有作用卻很貴的條件——
     * 它會讓優化器放棄主鍵反向掃描、退回索引加排序，
     * 正好踩中 ADR-0021 要避開的那個 filesort 懸崖。
     */
    public boolean coversAll(Set<Long> ids) {
        return ids.containsAll(allIds);
    }

    public int size() {
        return allIds.size();
    }
}
