package com.flashsale.infrastructure.adapter.out.search;

import com.flashsale.application.port.in.dto.ProductSearchResult;
import com.flashsale.domain.catalog.Product;

import java.math.BigDecimal;

/** 商品的可搜尋表述。 */
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
