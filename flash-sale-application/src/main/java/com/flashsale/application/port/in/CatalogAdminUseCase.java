package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ProductView;

/**
 * 商品上下架。
 *
 * <p>Catalog 先前<b>沒有任何寫入端點</b>，商品是遷移種進去的——
 * 沒有寫入就沒有領域事件，也就沒有東西能驅動搜尋索引（ADR-0012 決策 6）。
 *
 * <p>營運後台本來就在 P4 的清單上，只是搜尋讓它變成必須先做的那一個。
 */
public interface CatalogAdminUseCase {

    ProductView putOnShelf(Long productId);

    /**
     * 下架。
     *
     * <p><b>下架不刪資料</b>：歷史訂單仍需要追溯「這是哪個商品」。
     * 它只會從搜尋索引移除，資料庫那筆保留。
     */
    ProductView takeOffShelf(Long productId);
}
