package com.flashsale.application.service;

import com.flashsale.application.port.out.SearchIndexMetrics;
import com.flashsale.application.port.in.SearchIndexReconciliationUseCase;
import com.flashsale.application.port.in.dto.SearchIndexReconciliation;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.application.port.out.ProductSearchIndex;
import com.flashsale.domain.catalog.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 搜尋索引對帳（ADR-0012）。 */
@Service
public class SearchIndexReconciliationService implements SearchIndexReconciliationUseCase {

    private static final Logger log =
            LoggerFactory.getLogger(SearchIndexReconciliationService.class);

    private final ProductSearchIndex searchIndex;
    private final ProductRepository productRepository;
    private final SearchIndexMetrics metrics;

    public SearchIndexReconciliationService(ProductSearchIndex searchIndex,
                                            ProductRepository productRepository,
                                            SearchIndexMetrics metrics) {
        this.searchIndex = searchIndex;
        this.productRepository = productRepository;
        this.metrics = metrics;
    }

    @Override
    public SearchIndexReconciliation reconcile(boolean repair) {
        Set<Long> onShelf = productRepository.findOnShelfIds();
        Set<Long> indexed = searchIndex.allIndexedIds();

        List<Long> missing = onShelf.stream().filter(id -> !indexed.contains(id)).toList();
        List<Long> orphaned = indexed.stream().filter(id -> !onShelf.contains(id)).toList();

        long repaired = repair ? repair(missing, orphaned) : 0;

        metrics.recordReconciliation(missing.size(), orphaned.size());
        if (!missing.isEmpty() || !orphaned.isEmpty()) {
            // 只在有偏差時記 warn。每一輪都記的話，這行日誌會被當成背景雜訊而沒有人看
            log.warn("搜尋索引與資料庫不一致：缺少 {} 筆、多出 {} 筆，已修復 {} 筆",
                    missing.size(), orphaned.size(), repaired);
        }
        return SearchIndexReconciliation.of(indexed.size(), onShelf.size(),
                missing, orphaned, repaired);
    }

    /** 修復差異。 */
    private long repair(List<Long> missing, List<Long> orphaned) {
        List<Long> all = new ArrayList<>(missing);
        all.addAll(orphaned);

        long repaired = 0;
        for (Long productId : all) {
            try {
                Optional<Product> product = productRepository.findById(productId);
                if (product.isPresent() && product.get().status().isPurchasable()) {
                    searchIndex.index(product.get());
                } else {
                    searchIndex.remove(productId);
                }
                repaired++;
            } catch (RuntimeException e) {
                log.warn("修復商品 {} 的搜尋索引失敗，下一輪會再試", productId, e);
            }
        }
        return repaired;
    }
}
