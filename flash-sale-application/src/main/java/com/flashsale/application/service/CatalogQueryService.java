package com.flashsale.application.service;

import com.flashsale.application.port.in.CatalogQueryUseCase;
import com.flashsale.application.port.in.dto.CategoryView;
import com.flashsale.application.port.in.dto.ProductPage;
import com.flashsale.application.port.in.dto.ProductView;
import com.flashsale.application.port.out.CategoryRepository;
import com.flashsale.application.port.in.dto.SkuStockView;
import com.flashsale.application.port.out.InventoryRepository;
import com.flashsale.domain.inventory.Inventory;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.domain.catalog.CategoryTree;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.ProductCursor;
import com.flashsale.domain.catalog.ProductSort;
import com.flashsale.domain.catalog.ProductSummary;
import com.flashsale.domain.catalog.Sku;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Set;

/**
 * 商品目錄查詢服務。
 *
 * <p>分頁上限刻意設得保守：商品列表是對外開放的端點，
 * 沒有上限的話任何人都能用 {@code size=1000000} 讓資料庫掃全表。
 */
@Service
public class CatalogQueryService implements CatalogQueryUseCase {

    private static final int MAX_PAGE_SIZE = 100;

    /** 批次 SKU 查詢的上限，與購物車的品項上限一致。 */
    private static final int MAX_SKU_LOOKUP = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final InventoryRepository inventoryRepository;

    public CatalogQueryService(ProductRepository productRepository,
                               CategoryRepository categoryRepository,
                               InventoryRepository inventoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductPage listProducts(Long categoryId, String sortName, String cursor, int size) {
        int safeSize = Math.clamp(size <= 0 ? DEFAULT_PAGE_SIZE : size, 1, MAX_PAGE_SIZE);
        ProductSort sort = ProductSort.parse(sortName);

        // 多取一筆來判斷還有沒有下一頁，而不是再打一次 COUNT(*)——
        // 在 5 萬列上那個 count 比查詢本身還貴，而它只是為了決定一個布林值
        List<ProductSummary> rows = productRepository.findOnShelfSummaries(
                resolveCategoryFilter(categoryId), sort, ProductCursor.decode(cursor), safeSize + 1);

        boolean hasMore = rows.size() > safeSize;
        List<ProductSummary> pageRows = hasMore ? rows.subList(0, safeSize) : rows;
        if (pageRows.isEmpty()) {
            return ProductPage.empty();
        }

        // 游標取自這一頁最後一筆，而它是倉庫依當次排序鍵產生的（ADR-0021）
        String nextCursor = hasMore ? pageRows.get(pageRows.size() - 1).cursor() : null;
        return ProductPage.of(pageRows.stream().map(ProductView::fromSummary).toList(), nextCursor);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SkuStockView> findStock(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        // 與批次 SKU 查詢同一個上限：沒有上限的話，任何人都能用一個
        // 超長的 id 清單讓資料庫做一次大範圍查詢
        List<Long> capped = skuIds.stream().distinct().limit(MAX_SKU_LOOKUP).toList();

        Map<Long, Integer> availableBySku = inventoryRepository.findBySkuIds(capped).stream()
                .collect(Collectors.toMap(Inventory::skuId, Inventory::available));

        return capped.stream()
                // 查不到庫存列的當成缺貨，不是「無限有貨」——
                // 猜錯方向的代價是使用者買到系統沒有的東西
                .map(skuId -> availableBySku.containsKey(skuId)
                        ? SkuStockView.of(skuId, availableBySku.get(skuId))
                        : SkuStockView.unknown(skuId))
                .toList();
    }

    /**
     * 把「點了哪個類目」翻譯成「要涵蓋哪些類目」（ADR-0022）。
     *
     * <p>回傳 {@code null} 代表不篩選。<b>子樹涵蓋整棵樹時刻意回 null</b>：
     * 點根類目等於「全部商品」，此時那個包含每一個類目 ID 的
     * {@code in (...)} 沒有任何作用，卻會讓優化器放棄主鍵反向掃描、
     * 退回索引加排序，正好踩中 ADR-0021 要避開的懸崖。
     */
    private Set<Long> resolveCategoryFilter(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        CategoryTree tree = CategoryTree.of(categoryRepository.findAll());
        Set<Long> subtree = tree.withDescendants(categoryId);
        return tree.coversAll(subtree) ? null : subtree;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductView findProduct(Long productId) {
        return productRepository.findById(productId)
                .map(ProductView::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SkuLookup> findSkus(List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return List.of();
        }
        // 上限與購物車的品項上限一致——沒有上限的話，
        // 這個匿名端點就能被拿來一次撈走整個目錄
        List<Long> capped = skuIds.size() > MAX_SKU_LOOKUP
                ? skuIds.subList(0, MAX_SKU_LOOKUP)
                : skuIds;

        List<SkuLookup> result = new ArrayList<>();
        for (Product product : productRepository.findBySkuIds(capped)) {
            for (Sku sku : product.skus()) {
                if (capped.contains(sku.id())) {
                    result.add(new SkuLookup(sku.id(), product.id(), product.name(),
                            sku.spec().display(), sku.price(),
                            product.status().isPurchasable() && sku.isPurchasable()));
                }
            }
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryView> categoryTree() {
        return CategoryView.buildTree(categoryRepository.findAll());
    }
}
