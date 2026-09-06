package com.flashsale.application.port.in.dto;

import com.flashsale.domain.catalog.Category;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 類目樹節點。 */
public record CategoryView(
        Long categoryId,
        String name,
        int level,
        List<CategoryView> children
) {

    /** 把扁平清單組成樹。 */
    public static List<CategoryView> buildTree(List<Category> flat) {
        Map<Long, List<Category>> byParent = flat.stream()
                .filter(category -> !category.isRoot())
                .collect(Collectors.groupingBy(Category::parentId));

        return flat.stream()
                .filter(Category::isRoot)
                .sorted((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()))
                .map(root -> toNode(root, byParent))
                .toList();
    }

    private static CategoryView toNode(Category category, Map<Long, List<Category>> byParent) {
        List<CategoryView> children = new ArrayList<>();
        byParent.getOrDefault(category.id(), List.of()).stream()
                .sorted((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()))
                .forEach(child -> children.add(toNode(child, byParent)));

        return new CategoryView(category.id(), category.name(), category.level(), List.copyOf(children));
    }
}
