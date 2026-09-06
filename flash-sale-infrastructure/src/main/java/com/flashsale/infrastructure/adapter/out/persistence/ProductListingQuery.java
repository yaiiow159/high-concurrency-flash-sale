package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.domain.catalog.ProductCursor;
import com.flashsale.domain.catalog.ProductSort;

import java.util.Collection;

/** 組出商品列表的 SQL。 */
final class ProductListingQuery {

    private ProductListingQuery() {
    }

    /** 排序值的 SQL 運算式；依 id 排序時為 {@code null}。 */
    static String sortExpression(ProductSort sort) {
        return switch (sort) {
            case NEWEST -> null;
            case PRICE_ASC, PRICE_DESC -> "p.lowest_price";
            case BEST_SELLING -> "coalesce(ps.sold_quantity, 0)";
            case RATING -> "coalesce(pr.rating_sum / nullif(pr.rating_count, 0), 0)";
        };
    }

    static String build(ProductSort sort, Collection<Long> categoryIds, ProductCursor cursor,
                        boolean hasMinPrice, boolean hasMaxPrice) {
        String sortValue = sortExpression(sort);
        // 排序值要跟著回來——下一頁的游標需要它。
        // 少了這一欄，非唯一排序鍵的游標就組不出來，而那要翻到第二頁才會發現
        StringBuilder sql = new StringBuilder("select p.id, p.category_id, p.name, p.brand, ")
                .append(sortValue == null ? "null" : sortValue)
                .append(" as sort_value from product p ");

        // 只在需要的時候 join。銷量與評分各是一張表，
        // 依 id 排序時把它們拉進來只是白付一次 join
        if (sort == ProductSort.BEST_SELLING) {
            sql.append("left join product_sales ps on ps.product_id = p.id\n");
        }
        if (sort == ProductSort.RATING) {
            sql.append("left join product_rating pr on pr.product_id = p.id\n");
        }

        sql.append("where p.status = 'ON_SHELF'\n");
        if (categoryIds != null && !categoryIds.isEmpty()) {
            sql.append("  and p.category_id in (:categoryIds)\n");
        }
        // 沒有可購買規格的商品不參與價格排序——它的 lowest_price 是 NULL，
        // 而 NULL 在排序裡的位置是一個沒有正確答案的問題
        if (sort == ProductSort.PRICE_ASC || sort == ProductSort.PRICE_DESC) {
            sql.append("  and p.lowest_price is not null\n");
        }
        // 價格區間比對的是**最低價**，與列表顯示的「NT$ x 起」同一個數字。
        // 用別的欄位比的話，使用者篩了 1000 以下卻看到標價 1200 的商品
        if (hasMinPrice) {
            sql.append("  and p.lowest_price >= :minPrice\n");
        }
        if (hasMaxPrice) {
            sql.append("  and p.lowest_price <= :maxPrice\n");
        }
        if (cursor != null) {
            sql.append("  and ").append(keysetPredicate(sort)).append('\n');
        }

        sql.append("order by ").append(orderBy(sort)).append('\n');
        sql.append("limit :limit");
        return sql.toString();
    }

    private static String keysetPredicate(ProductSort sort) {
        String expression = sortExpression(sort);
        if (expression == null) {
            return "p.id < :cursorId";
        }
        String comparison = ascending(sort) ? ">" : "<";
        // 展開成兩段而不是 row constructor：價格升冪時 id 仍然降冪，
        // 而 (a, b) > (?, ?) 沒辦法讓兩欄各走各的方向
        return "(%s %s :cursorSort or (%s = :cursorSort and p.id < :cursorId))"
                .formatted(expression, comparison, expression);
    }

    private static String orderBy(ProductSort sort) {
        String expression = sortExpression(sort);
        if (expression == null) {
            return "p.id desc";
        }
        // id 永遠降冪當決勝鍵，與 keyset 判斷式裡的 `p.id < :cursorId` 一致。
        // 兩邊不一致的話分頁會跳號，而那要翻到第幾頁才看得出來
        return "%s %s, p.id desc".formatted(expression, ascending(sort) ? "asc" : "desc");
    }

    private static boolean ascending(ProductSort sort) {
        return sort == ProductSort.PRICE_ASC;
    }
}
