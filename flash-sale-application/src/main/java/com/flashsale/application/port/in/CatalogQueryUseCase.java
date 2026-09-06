package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.CategoryView;
import com.flashsale.application.port.in.dto.ProductPage;
import com.flashsale.application.port.in.dto.ProductView;
import com.flashsale.application.port.in.dto.SkuStockView;

import java.math.BigDecimal;
import java.util.List;

/** 商品目錄查詢入站埠。 */
public interface CatalogQueryUseCase {

    /**
     * 商品列表。
     *
     * @param categoryId {@code null} 表示不限類目
     */
    /**
     * 商店的商品列表（ADR-0021 keyset 分頁、ADR-0022 類目子樹）。
     *
     * @param categoryId 篩選的類目；<b>包含它的整棵子樹</b>。
     * 只比對單一節點的話，點中間層會得到空結果——
     * 商品只掛在葉節點上
     * @param sortName   排序方式的名稱；{@code null} 為預設（最新上架）。
     * 認不得的值<b>報錯而不是默默退回預設</b>——
     * 悄悄改成最新上架的話，使用者選了「價格由低到高」
     * 卻看到別的順序，而選單仍停在他選的那一項
     * @param cursor     上一頁最後一筆的游標；{@code null} 代表第一頁
     * @param minPrice   價格下限；{@code null} 為不限。比對的是最低價，
     * 與列表顯示的「NT$ x 起」同一個數字
     */
    ProductPage listProducts(Long categoryId, String sortName, String cursor,
                             BigDecimal minPrice, BigDecimal maxPrice, int size);

    /** 批次查庫存。 */
    /** 依 id 批次取上架商品，順序不保證，呼叫端自己排。 */
    List<ProductView> findProductsByIds(List<Long> productIds);

    List<SkuStockView> findStock(List<Long> skuIds);

    /**
     * 商品詳情，含所有 SKU。
     *
     * @throws com.flashsale.domain.shared.BusinessException 商品不存在時
     */
    ProductView findProduct(Long productId);

    /** 依多個 SKU 批次查詢，供未登入的本地購物車取得商品名與價格。 */
    List<SkuLookup> findSkus(List<Long> skuIds);

    /** 購物車定價所需的最小資訊；刻意不含描述與其他 SKU。 */
    record SkuLookup(Long skuId, Long productId, String productName,
                     String specDisplay, java.math.BigDecimal price, boolean purchasable) {
    }

    /** 完整類目樹。 */
    List<CategoryView> categoryTree();
}
