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

    /** 完整類目樹。 */
    List<CategoryView> categoryTree();
}
