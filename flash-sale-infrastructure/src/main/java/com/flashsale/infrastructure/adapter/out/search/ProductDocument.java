package com.flashsale.infrastructure.adapter.out.search;

import com.flashsale.application.port.in.dto.ProductSearchResult;
import com.flashsale.domain.catalog.Product;

import java.math.BigDecimal;

/**
 * 商品的可搜尋表述。
 *
 * <h2>刻意不放庫存</h2>
 *
 * <p>庫存每秒都在變，而這份索引的同步延遲是數秒。放進來的話，
 * 搜尋結果會顯示一個必定過時的數字——而使用者會相信它。
 * 庫存一律從 Redis 讀（ADR-0002）。
 *
 * <p>價格放，但那是「索引當下的最低價」，只用於列表展示與排序。
 * 點進商品頁後會從 Catalog 重新讀；結帳完全不碰這份索引（ADR-0012 決策 3）。
 */
public record ProductDocument(
        Long productId,
        String name,
        String brand,
        String description,
        Long categoryId,
        BigDecimal lowestPrice
) {

    public static ProductDocument from(Product product) {
        return new ProductDocument(
                product.id(),
                product.name(),
                product.brand(),
                product.description(),
                product.categoryId(),
                product.lowestPrice());
    }

    public ProductSearchResult.Hit toHit() {
        return new ProductSearchResult.Hit(productId, name, brand, categoryId, lowestPrice);
    }
}
