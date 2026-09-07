package com.flashsale.application.service;

import com.flashsale.application.port.in.ProductSearchUseCase;
import com.flashsale.application.port.in.dto.ProductSearchResult;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.application.port.out.ProductSearchIndex;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.event.ProductIndexChangedEvent;
import com.flashsale.domain.shared.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 商品搜尋。 */
@Service
public class ProductSearchService implements ProductSearchUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProductSearchService.class);

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
        return search(new SearchRequest(keyword, categoryId, brand,
                null, null, null, false, null, page, size));
    }

    @Override
    public ProductSearchResult search(SearchRequest request) {
        Page paging = Page.of(request.page(), request.size(), MAX_PAGE_SIZE);
        return searchIndex.search(new ProductSearchIndex.SearchQuery(
                request.keyword() == null ? "" : request.keyword().trim(),
                request.categoryId(), request.brand(),
                request.minPrice(), request.maxPrice(), request.minRating(),
                request.inStockOnly(), parseSort(request.sort()),
                paging.number(), paging.size()));
    }

    /** 不認得的排序名回退成相關性，而不是 400——那是使用者改網址就會撞到的事。 */
    private static ProductSearchIndex.SearchSort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return ProductSearchIndex.SearchSort.RELEVANCE;
        }
        try {
            return ProductSearchIndex.SearchSort.valueOf(sort.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ProductSearchIndex.SearchSort.RELEVANCE;
        }
    }

    /** {@inheritDoc} */
    @Override
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
    /** 建議最多幾筆。太多會讓下拉選單蓋住整個畫面，而使用者只會看前幾個。 */
    private static final int MAX_SUGGESTIONS = 8;

    @Override
    public List<String> suggest(String keyword) {
        return searchIndex.suggest(keyword, MAX_SUGGESTIONS);
    }

}
