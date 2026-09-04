package com.flashsale.application.service;

import com.flashsale.application.port.in.ProductSearchUseCase;
import com.flashsale.application.port.in.dto.ProductSearchResult;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.application.port.out.ProductSearchIndex;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.event.ProductIndexChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 商品搜尋。
 *
 * <h2>索引更新讀的是「當下的商品」，不是事件裡的內容</h2>
 *
 * <p>事件只帶 ID（見 {@code ProductIndexChangedEvent} 的說明），
 * 因此這裡回頭讀 Catalog。好處是索引想加欄位時事件完全不用動；
 * 代價是每則事件多一次資料庫讀取，而商品變更是低頻操作。
 *
 * <h2>下架與「查不到」走同一條路</h2>
 *
 * <p>兩者都從索引移除。商品被硬刪（理論上不該發生）時，
 * 讀不到就移除，索引不會留下一筆指向不存在商品的殘骸。
 */
@Service
public class ProductSearchService implements ProductSearchUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProductSearchService.class);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final ProductSearchIndex searchIndex;
    private final ProductRepository productRepository;

    public ProductSearchService(ProductSearchIndex searchIndex,
                                ProductRepository productRepository) {
        this.searchIndex = searchIndex;
        this.productRepository = productRepository;
    }

    @Override
    public ProductSearchResult search(String keyword, Long categoryId, String brand,
                                      int page, int size) {
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.clamp(size, 1, MAX_PAGE_SIZE);
        return searchIndex.search(new ProductSearchIndex.SearchQuery(
                keyword == null ? "" : keyword.trim(),
                categoryId, brand, Math.max(page, 0), pageSize));
    }

    @Override
    @Transactional(readOnly = true)
    public void applyIndexChange(ProductIndexChangedEvent event) {
        Optional<Product> product = productRepository.findById(event.productId());
        if (product.isEmpty() || !product.get().status().isPurchasable()) {
            searchIndex.remove(event.productId());
            log.debug("商品 {} 已從搜尋索引移除", event.productId());
            return;
        }
        searchIndex.index(product.get());
        log.debug("商品 {} 已寫入搜尋索引", event.productId());
    }

    @Override
    public long reindex() {
        long indexed = searchIndex.reindexAll();
        log.info("搜尋索引重建完成，共 {} 筆", indexed);
        return indexed;
    }
}
