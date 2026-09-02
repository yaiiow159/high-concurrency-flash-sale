package com.flashsale.application.service;

import com.flashsale.application.port.in.CatalogQueryUseCase;
import com.flashsale.domain.catalog.Sku;
import com.flashsale.domain.catalog.Product;
import com.flashsale.application.port.in.dto.CategoryView;
import com.flashsale.application.port.in.dto.ProductView;
import com.flashsale.application.port.out.CategoryRepository;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

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

    public CatalogQueryService(ProductRepository productRepository,
                               CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductView> listProducts(Long categoryId, int page, int size) {
        int safeSize = Math.clamp(size <= 0 ? DEFAULT_PAGE_SIZE : size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(0, page);

        return productRepository.findOnShelf(categoryId, safeSize, safePage * safeSize).stream()
                // 列表不帶描述與 SKU 清單——列表頁用不到，卻會讓回應大上數倍
                .map(product -> ProductView.from(product).asSummary())
                .toList();
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
