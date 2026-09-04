package com.flashsale.application.service;

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

/**
 * 搜尋索引對帳（ADR-0012）。
 *
 * <h2>這裡的自動修復可以被證明安全，庫存那邊不行</h2>
 *
 * <p>專案的既有規則是「對帳的自動修復預設關閉」（CLAUDE.md 第 8 條），
 * 而這裡刻意反過來。理由不是「搜尋比較不重要」，是<b>修復動作的性質不同</b>：
 *
 * <ul>
 *   <li>庫存對帳看到偏差時，「退庫」是一個<b>新的決定</b>——
 *       它可能與正在佇列裡排隊的請求衝突，把少賣變成超賣。
 *       有 bug 的自動修復，破壞力大於它要修的問題</li>
 *   <li>索引對帳的修復動作，與<b>那個還沒被消費的事件會做的事一模一樣</b>：
 *       讀當下的商品狀態，上架就寫進索引、否則移除。
 *       提早做一次不會產生任何新的狀態——事件晚點到達時寫的是同一份內容</li>
 * </ul>
 *
 * <p>換句話說：這裡的「修復」不是猜測，是把一件遲早要發生的事提前做完。
 * 也因此不需要庫存那種寬限期——沒有「還在飛的請求」會被誤判。
 *
 * <h2>不碰資金、不碰庫存</h2>
 *
 * <p>索引寫入是冪等覆寫，寫錯了下一次對帳會再修正回來。
 * 這是它與所有其他對帳最大的差別，也是自動修復能成立的前提。
 */
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

    /**
     * 修復差異。
     *
     * <p>每一筆都重讀當下的商品狀態再決定寫或刪——不直接用對帳當時的集合。
     * 對帳到修復之間可能又有變更，用舊集合會把剛下架的商品又寫回索引。
     *
     * <p>單筆失敗不中斷整批：一筆修不掉不該讓其他人的商品也繼續搜不到。
     * 沒修掉的那些下一輪還會被抓到。
     */
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
