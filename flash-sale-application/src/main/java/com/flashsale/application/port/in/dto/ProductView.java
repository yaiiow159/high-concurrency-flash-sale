package com.flashsale.application.port.in.dto;

import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.ProductSummary;
import com.flashsale.domain.catalog.Sku;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 商品詳情。 */
public record ProductView(
        Long productId,
        Long categoryId,
        String name,
        String brand,
        String description,
        String status,
        BigDecimal lowestPrice,
        List<SkuView> skus
) {

    public record SkuView(
            Long skuId,
            Map<String, String> spec,
            String specDisplay,
            BigDecimal price,
            boolean purchasable
    ) {
        static SkuView from(Sku sku) {
            return new SkuView(sku.id(), sku.spec().attributes(), sku.spec().display(),
                    sku.price(), sku.isPurchasable());
        }
    }

    public static ProductView from(Product product) {
        return new ProductView(
                product.id(),
                product.categoryId(),
                product.name(),
                product.brand(),
                product.description(),
                product.status().name(),
                product.lowestPrice(),
                product.skus().stream().map(SkuView::from).toList());
    }

    /** 列表用的精簡版：不帶描述與 SKU 清單。 */
    public static ProductView fromSummary(ProductSummary summary) {
        return new ProductView(summary.id(), summary.categoryId(), summary.name(),
                summary.brand(), null, summary.status().name(),
                summary.lowestPrice(), List.of());
    }
}
