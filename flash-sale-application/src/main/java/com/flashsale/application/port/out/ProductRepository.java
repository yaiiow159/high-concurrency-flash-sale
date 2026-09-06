package com.flashsale.application.port.out;

import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.PriceRange;
import com.flashsale.domain.catalog.ProductCursor;
import com.flashsale.domain.catalog.ProductSort;
import com.flashsale.domain.catalog.ProductSummary;
import com.flashsale.domain.catalog.Sku;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 商品持久化埠（出站）。 */
public interface ProductRepository {

    Product save(Product product);

    /** 依 ID 取商品，含其所有 SKU——聚合根被載入後就該是完整的。 */
    Optional<Product> findById(Long productId);

    /** 更新商品的上架狀態。 */
    Product updateStatus(Product product);

    /** 所有已上架商品的 ID。 */
    java.util.Set<Long> findOnShelfIds();

    /** 依 id 批次取上架商品。首頁的人工選品用，已下架的不會回來。 */
    List<ProductSummary> findOnShelfSummariesByIds(List<Long> productIds);

    /**
     * 依類目列出已上架商品。
     *
     * @param categoryId {@code null} 表示不限類目
     */
    List<Product> findOnShelf(Long categoryId, int limit, int offset);

    /** 商店列表查詢：回傳<b>摘要</b>，keyset 分頁（ADR-0021）。 */
    List<ProductSummary> findOnShelfSummaries(Collection<Long> categoryIds,
                                              ProductSort sort, ProductCursor cursor,
                                              PriceRange priceRange, int limit);

    /** 後台用：列出<b>所有狀態</b>的商品。 */
    List<Product> findAllByStatus(String status, int limit, int offset);

    /** 依 SKU 反查其所屬商品。 */
    Optional<Product> findBySkuId(Long skuId);

    /** 批次取 SKU，供結帳時一次取得多個品項的價格與狀態。 */
    List<Sku> findSkusByIds(List<Long> skuIds);

    /** 依多個 SKU 反查商品，供購物車一次帶出所有品項的名稱與價格。 */
    List<Product> findBySkuIds(List<Long> skuIds);

    /** 關鍵字模糊比對——<b>搜尋引擎故障時的降級路徑</b>（ADR-0012 決策 4）。 */
    List<Product> searchByKeyword(String keyword, Long categoryId, String brand,
                                  int limit, int offset);
}
