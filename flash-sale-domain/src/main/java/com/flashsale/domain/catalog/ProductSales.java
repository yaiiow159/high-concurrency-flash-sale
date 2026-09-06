package com.flashsale.domain.catalog;

/** 商品的銷量聚合。 */
public record ProductSales(Long productId, long soldQuantity, long orderCount) {

    public static ProductSales none(Long productId) {
        return new ProductSales(productId, 0, 0);
    }
}
