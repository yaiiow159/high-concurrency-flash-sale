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

/** 類目樹的查詢視角（ADR-0022）。 */
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

    /** 這個類目與它所有子孫的 ID。 */
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

    /** 這組 ID 是否已經涵蓋整棵樹。 */
    public boolean coversAll(Set<Long> ids) {
        return ids.containsAll(allIds);
    }

    public int size() {
        return allIds.size();
    }
}
