package com.flashsale.infrastructure.adapter.out.search;

import com.flashsale.application.port.in.dto.ProductSearchResult;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.review.ProductRating;

import java.math.BigDecimal;

/**
 * 商品的可搜尋表述。評分與有貨是<b>快照</b>：搜尋文件是投影，不是聚合根，
 * 允許落後幾秒；點進商品頁會重新從 Catalog 讀，結帳完全不碰這份索引。
 */
public record ProductDocument(
        Long productId,
        String name,
        String brand,
        String description,
        Long categoryId,
        BigDecimal lowestPrice,
        BigDecimal ratingAverage,
        int ratingCount,
        boolean inStock,
        /** epoch 毫秒。ES client 自帶的 Jackson 沒掛 JSR-310 模組，Instant 會直接序列化失敗 */
        long createdAt
) {

    public static ProductDocument from(Product product, ProductRating rating, boolean inStock) {
        return new ProductDocument(
                product.id(),
                product.name(),
                product.brand(),
                product.description(),
                product.categoryId(),
                product.lowestPrice(),
                rating.average(),
                rating.ratingCount(),
                inStock,
                product.createdAt().toEpochMilli());
    }

    public ProductSearchResult.Hit toHit() {
        return new ProductSearchResult.Hit(productId, name, brand, categoryId, lowestPrice,
                ratingAverage == null ? BigDecimal.ZERO : ratingAverage, ratingCount, inStock);
    }
}
