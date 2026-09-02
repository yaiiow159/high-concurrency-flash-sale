package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.CategoryView;
import com.flashsale.application.port.in.dto.ProductView;

import java.util.List;

/** 商品目錄查詢入站埠。 */
public interface CatalogQueryUseCase {

    /**
     * 商品列表。
     *
     * @param categoryId {@code null} 表示不限類目
     */
    List<ProductView> listProducts(Long categoryId, int page, int size);

    /**
     * 商品詳情，含所有 SKU。
     *
     * @throws com.flashsale.domain.shared.BusinessException 商品不存在時
     */
    ProductView findProduct(Long productId);

    /**
     * 依多個 SKU 批次查詢，供未登入的本地購物車取得商品名與價格。
     *
     * <p><b>批次而非逐筆</b>：本地購物車最多 50 個品項，
     * 逐筆查就是 50 次往返，而購物車頁是使用者反覆重整的頁面。
     *
     * <p>查不到的 SKU 直接不出現在結果裡，不拋例外——
     * 放了幾天的本地購物車裡有商品被刪除是完全正常的。
     */
    List<SkuLookup> findSkus(List<Long> skuIds);

    /** 購物車定價所需的最小資訊；刻意不含描述與其他 SKU。 */
    record SkuLookup(Long skuId, Long productId, String productName,
                     String specDisplay, java.math.BigDecimal price, boolean purchasable) {
    }

    /** 完整類目樹。 */
    List<CategoryView> categoryTree();
}
