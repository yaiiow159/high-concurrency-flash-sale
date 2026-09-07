package com.flashsale.application.port.in.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 搜尋結果（ADR-0012）。 */
public record ProductSearchResult(
        List<Hit> hits,
        long total,
        Map<String, Long> facets,
        boolean degraded
) {

    /** 一筆命中。 */
    /** 評分與有貨是索引當下的快照，允許落後；結帳完全不碰這份索引。 */
    public record Hit(
            Long productId,
            String name,
            String brand,
            Long categoryId,
            BigDecimal lowestPrice,
            BigDecimal ratingAverage,
            int ratingCount,
            boolean inStock
    ) {
    }

    public static ProductSearchResult empty(boolean degraded) {
        return new ProductSearchResult(List.of(), 0L, Map.of(), degraded);
    }
}
