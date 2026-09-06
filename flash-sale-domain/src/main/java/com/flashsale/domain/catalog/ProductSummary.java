package com.flashsale.domain.catalog;

import java.math.BigDecimal;

/** 列表用的商品摘要。 */
public record ProductSummary(
        Long id,
        Long categoryId,
        String name,
        String brand,
        ProductStatus status,
        BigDecimal lowestPrice,
        String cursor
) {

    /** 換一個最低價，其餘不變。倉庫批次補價格時用。 */
    public ProductSummary withLowestPrice(BigDecimal price) {
        return new ProductSummary(id, categoryId, name, brand, status, price, cursor);
    }
}
