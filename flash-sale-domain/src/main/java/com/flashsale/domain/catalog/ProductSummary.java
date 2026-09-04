package com.flashsale.domain.catalog;

import java.math.BigDecimal;

/**
 * 列表用的商品摘要。
 *
 * <h2>為什麼不直接用 {@link Product} 聚合</h2>
 *
 * <p>聚合帶著完整的 SKU 清單，而列表<b>只需要一個最低價</b>。
 * 先前的做法是把聚合整個組出來、再用 {@code asSummary()} 把 SKU 丟掉——
 * 結果是每一頁 20 筆商品觸發 20 次 lazy 載入（實測 {@code size=20} 打 23 次
 * SELECT、{@code size=100} 打 104 次，線性成長），
 * 而那些 SKU 全部在下一行被丟棄。
 *
 * <p>列表是<b>讀模型</b>，不是聚合。讓它有自己的型別，
 * 「列表需要什麼」就變成編譯期看得見的事，
 * 而不是靠一個註解提醒後人不要碰 {@code skus()}。
 *
 * @param lowestPrice 最低可購買 SKU 的價格；沒有可購買的 SKU 時為 {@code null}
 * @param cursor      從這一列往下翻的游標（ADR-0021）。
 *                    <b>由倉庫產生而不是呼叫端組裝</b>——只有倉庫知道
 *                    這次是依哪個鍵排序的，而游標的內容必須與排序鍵一致。
 *                    讓上層自己拼，換一種排序就會有一個地方忘記改
 */
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
