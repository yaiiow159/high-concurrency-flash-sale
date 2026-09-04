package com.flashsale.application.port.in.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 搜尋結果（ADR-0012）。
 *
 * <p><b>{@code degraded} 不是裝飾用的。</b> 搜尋引擎故障時系統會退回
 * 資料庫的模糊比對——那份結果沒有相關性排序、也沒有分面統計。
 * 不告訴前端的話，使用者會以為「就是搜不到」而不是「現在搜得不準」，
 * 然後去客服說商品不見了。
 *
 * @param facets   分面統計（品牌 → 筆數）。降級時為空——
 *                 資料庫那條路不做分面，硬湊一份出來只會是錯的
 * @param degraded true 代表這份結果來自降級路徑
 */
public record ProductSearchResult(
        List<Hit> hits,
        long total,
        Map<String, Long> facets,
        boolean degraded
) {

    /**
     * 一筆命中。
     *
     * <p><b>只有識別與展示用的欄位，沒有庫存。</b>
     * 價格是索引當下的快照，允許落後數秒；點進商品頁後會重新從 Catalog 讀。
     * 庫存完全不放——它每秒都在變，放進一份延遲數秒的索引裡只會是錯的。
     */
    public record Hit(
            Long productId,
            String name,
            String brand,
            Long categoryId,
            BigDecimal lowestPrice
    ) {
    }

    public static ProductSearchResult empty(boolean degraded) {
        return new ProductSearchResult(List.of(), 0L, Map.of(), degraded);
    }
}
