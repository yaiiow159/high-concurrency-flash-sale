package com.flashsale.application.port.in.dto;

import java.util.List;

/** 商品列表的一頁（ADR-0021）。 */
public record ProductPage(
        List<ProductView> items,
        String nextCursor,
        boolean hasMore
) {

    public static ProductPage of(List<ProductView> items, String nextCursor) {
        return new ProductPage(items, nextCursor, nextCursor != null);
    }

    public static ProductPage empty() {
        return new ProductPage(List.of(), null, false);
    }
}
