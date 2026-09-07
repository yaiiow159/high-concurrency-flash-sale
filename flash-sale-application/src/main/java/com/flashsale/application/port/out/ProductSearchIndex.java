package com.flashsale.application.port.out;

import java.math.BigDecimal;
import java.util.List;
import com.flashsale.application.port.in.dto.ProductSearchResult;
import com.flashsale.domain.catalog.Product;

/** 商品搜尋索引埠（出站）。 */
public interface ProductSearchIndex {

    /** 寫入或覆寫一筆商品文件。 */
    void index(Product product);

    /** 從索引移除。 */
    void remove(Long productId);

    /** 搜尋。 */
    ProductSearchResult search(SearchQuery query);

    /** 整份重建。 */
    long reindexAll();

    /** 索引裡目前有哪些商品 ID。 */
    java.util.Set<Long> allIndexedIds();

    /**
     * 查詢條件。
     *
     * @param keyword    關鍵字；空字串代表不限，用於純分類瀏覽
     * @param categoryId 類目篩選，{@code null} 為不限
     * @param brand      品牌篩選，{@code null} 為不限
     */
    /** 搜尋建議：依前綴比對商品名與品牌，回傳去重後的候選字。 */
    List<String> suggest(String prefix, int limit);

    /**
     * @param minRating   最低平均星等（含），{@code null} 為不限
     * @param inStockOnly 只列有貨的
     */
    record SearchQuery(String keyword, Long categoryId, String brand,
                       BigDecimal minPrice, BigDecimal maxPrice, Integer minRating,
                       boolean inStockOnly, SearchSort sort, int page, int size) {
    }

    /** 排序。關鍵字為空時 RELEVANCE 退化為 NEWEST——沒有關鍵字就沒有相關性可言。 */
    enum SearchSort { RELEVANCE, PRICE_ASC, PRICE_DESC, RATING, NEWEST }
}
