package com.flashsale.application.port.out;

import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.Sku;

import java.util.List;
import java.util.Optional;

/** 商品持久化埠（出站）。 */
public interface ProductRepository {

    Product save(Product product);

    /** 依 ID 取商品，含其所有 SKU——聚合根被載入後就該是完整的。 */
    Optional<Product> findById(Long productId);

    /**
     * 依類目列出已上架商品。
     *
     * @param categoryId {@code null} 表示不限類目
     */
    List<Product> findOnShelf(Long categoryId, int limit, int offset);

    /**
     * 依 SKU 反查其所屬商品。
     *
     * <p>秒殺活動引用的是 SKU，但要組出訂單行的商品快照需要商品名稱。
     */
    Optional<Product> findBySkuId(Long skuId);

    /** 批次取 SKU，供結帳時一次取得多個品項的價格與狀態。 */
    List<Sku> findSkusByIds(List<Long> skuIds);

    /**
     * 依多個 SKU 反查商品，供購物車一次帶出所有品項的名稱與價格。
     *
     * <p>逐筆查在 50 個品項的購物車上就是 50 次往返，
     * 而購物車頁正是使用者反覆重整的頁面。
     */
    List<Product> findBySkuIds(List<Long> skuIds);
}
