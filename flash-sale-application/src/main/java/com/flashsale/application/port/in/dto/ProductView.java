package com.flashsale.application.port.in.dto;

import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.Sku;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商品詳情。
 *
 * <p>刻意<b>不含庫存</b>：庫存變動極快，與商品的靜態描述放在一起，
 * 快取策略就無法區分兩者——而商品頁的靜態部分正是要被 CDN 長時間承接的。
 * 庫存由前端另外請求（與秒殺頁同一個手法）。
 */
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

    /** 列表用的精簡版，不帶描述與完整 SKU 清單。 */
    public ProductView asSummary() {
        return new ProductView(productId, categoryId, name, brand, null, status, lowestPrice, List.of());
    }
}
