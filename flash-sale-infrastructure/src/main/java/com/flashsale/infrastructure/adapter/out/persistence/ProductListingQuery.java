package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.domain.catalog.ProductCursor;
import com.flashsale.domain.catalog.ProductSort;

import java.util.Collection;

/** 組出商品列表的 SQL。 */
final class ProductListingQuery {

    private ProductListingQuery() {
    }

    /** 依排行排序時的驅動表；其餘為 null，代表由 product 驅動。 */
    private static String rankingTable(ProductSort sort) {
        return switch (sort) {
            case BEST_SELLING -> "product_sales ps";
            case RATING -> "product_rating pr";
            case NEWEST, PRICE_ASC, PRICE_DESC -> null;
        };
    }

    private static String rankingAlias(ProductSort sort) {
        return sort == ProductSort.BEST_SELLING ? "ps" : "pr";
    }

    /**
     * 決勝鍵要用驅動表自己的那一欄：{@code p.id} 與 {@code ps.product_id} 值相同，
     * 但 MySQL 不知道，寫成前者排序就落不到排行索引上。
     */
    private static String tieBreaker(ProductSort sort) {
        String ranking = rankingTable(sort);
        return ranking == null ? "p.id" : rankingAlias(sort) + ".product_id";
    }

    /** 排序值的 SQL 運算式；依 id 排序時為 {@code null}。 */
    static String sortExpression(ProductSort sort) {
        return switch (sort) {
            case NEWEST -> null;
            case PRICE_ASC, PRICE_DESC -> "p.lowest_price";
            case BEST_SELLING -> "ps.sold_quantity";
            case RATING -> "pr.average_rating";
        };
    }

    static String build(ProductSort sort, Collection<Long> categoryIds, ProductCursor cursor,
                        boolean hasMinPrice, boolean hasMaxPrice) {
        String sortValue = sortExpression(sort);
        // 排序值要跟著回來——下一頁的游標需要它。
        // 少了這一欄，非唯一排序鍵的游標就組不出來，而那要翻到第二頁才會發現
        String ranking = rankingTable(sort);
        StringBuilder sql = new StringBuilder("select ")
                .append(ranking == null ? "" : "straight_join ")
                .append("p.id, p.category_id, p.name, p.brand, ")
                .append(sortValue == null ? "null" : sortValue)
                .append(" as sort_value from ");

        // 依排行排序時由排行表驅動：反過來的話排序鍵在 join 進來的表上，
        // MySQL 只能掃完全部商品再 filesort。straight_join 是必要的——
        // optimizer 會因為 status 的選擇度堅持先掃 product，那讓排行索引完全用不到。
        //
        // 依賴「每個商品都有一列排行」這條不變量（V31 建立、
        // JpaProductRepository.save 維持）。
        if (ranking == null) {
            sql.append("product p ");
        } else {
            sql.append(ranking).append(" join product p on p.id = ")
                    .append(rankingAlias(sort)).append(".product_id").append('\n');
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
        return "(%s %s :cursorSort or (%s = :cursorSort and %s < :cursorId))"
                .formatted(expression, comparison, expression, tieBreaker(sort));
    }

    private static String orderBy(ProductSort sort) {
        String expression = sortExpression(sort);
        if (expression == null) {
            return "p.id desc";
        }
        // id 永遠降冪當決勝鍵，與 keyset 判斷式用同一欄。
        // 兩邊不一致的話分頁會跳號，而那要翻到第幾頁才看得出來
        return "%s %s, %s desc"
                .formatted(expression, ascending(sort) ? "asc" : "desc", tieBreaker(sort));
    }

    private static boolean ascending(ProductSort sort) {
        return sort == ProductSort.PRICE_ASC;
    }
}
