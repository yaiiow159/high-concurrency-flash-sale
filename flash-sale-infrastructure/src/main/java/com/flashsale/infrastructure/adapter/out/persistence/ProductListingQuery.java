package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.domain.catalog.ProductCursor;
import com.flashsale.domain.catalog.ProductSort;

import java.util.Collection;

/**
 * 組出商品列表的 SQL。
 *
 * <h2>為什麼是動態拼裝，而不是十個 {@code @Query}</h2>
 *
 * <p>五種排序 × 有沒有類目條件 = 十種組合。寫成十個具名方法，
 * 十份幾乎相同的 SQL 會各自漂移——改了 keyset 的判斷式而漏改其中一份，
 * 症狀是「某個排序方式下會跳過商品」，而那不會拋任何錯誤。
 *
 * <p>拼裝的部分<b>全部來自列舉</b>，沒有任何一段字串來自請求，
 * 因此不存在注入面。參數一律走 bind。
 *
 * <h2>keyset 的判斷式為什麼長這樣</h2>
 *
 * <p>非唯一的排序鍵必須配 id 當決勝鍵：
 * {@code (sort, id) < (:sortValue, :id)}。
 * 只比 sort 的話，同值的商品會被整批跳過（用 {@code <}）
 * 或整批重複（用 {@code <=}），而兩種都不會報錯。
 *
 * <p>MySQL 支援 row constructor 比較，但它在混合排序方向
 * （價格升冪 + id 降冪）時無法直接表達，因此展開成
 * {@code sort < ? or (sort = ? and id < ?)}——語意相同、方向可各自指定。
 */
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

    static String build(ProductSort sort, Collection<Long> categoryIds, ProductCursor cursor) {
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
