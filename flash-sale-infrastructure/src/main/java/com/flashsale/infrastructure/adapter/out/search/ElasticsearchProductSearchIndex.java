package com.flashsale.infrastructure.adapter.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.flashsale.application.port.in.dto.ProductSearchResult;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.application.port.out.ProductSearchIndex;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Elasticsearch 商品索引（ADR-0012）。
 *
 * <h2>搜尋失敗一律降級，不往上拋</h2>
 *
 * <p>對照專案既有的降級規則：庫存的 Redis 故障必須 fail-closed
 * （放行等於無上限超賣），限流的 Redis 故障可以 fail-open
 * （後面還有庫存這道關卡）。搜尋屬於後者而且更寬鬆——
 * <b>搜不準不會產生任何錯誤資料</b>，只是體驗變差。
 *
 * <p>因此 ES 掛掉時退回資料庫的模糊比對，並在結果上標記 {@code degraded}。
 * 讓整頁壞掉換不到任何東西。
 *
 * <h2>索引名帶版本，對外走 alias</h2>
 *
 * <p>ES 不允許改既有欄位的型別，因此任何 mapping 變更都必須重建。
 * 寫進 {@code products_vN}、完成後把 alias {@code products} 原子切換過去，
 * 舊索引留著可以立刻切回去。少了這一層，改一次 mapping 就是一次停機。
 */
@Component
public class ElasticsearchProductSearchIndex implements ProductSearchIndex {

    private static final Logger log =
            LoggerFactory.getLogger(ElasticsearchProductSearchIndex.class);

    /** 對外的固定名稱。實際索引是 {@code products_vN}，靠 alias 指過去。 */
    static final String ALIAS = "products";

    /** 重建時一次搬多少筆。太大會讓單一 bulk 請求逾時，太小則往返次數多。 */
    private static final int REINDEX_BATCH = 500;

    private static final String FACET_BRAND = "brand";

    /**
     * 重建進行中的目標索引名；沒有重建時為 {@code null}。
     *
     * <p><b>存在的理由是一個真實的競態。</b> 重建期間，消費端寫的是 alias
     * （也就是舊索引），而重建讀的是重建開始那一刻的資料庫快照。
     * 切換 alias 之後，重建期間發生的所有變更都會被丟掉——
     * 下架的商品復活、新上架的商品消失，而窗口是整個重建的時長，
     * 那正好是「有人在大量調整商品」的時候。
     *
     * <p>解法是重建期間雙寫。商品變更是一天幾十次的低頻操作，
     * 多寫一次的成本可以忽略，而且不需要任何鎖。
     */
    private final AtomicReference<String> rebuildTarget = new AtomicReference<>();

    private final ElasticsearchClient client;
    private final ProductIndexAdmin indexAdmin;
    private final ProductRepository productRepository;

    public ElasticsearchProductSearchIndex(ElasticsearchClient client,
                                           ProductIndexAdmin indexAdmin,
                                           ProductRepository productRepository) {
        this.client = client;
        this.indexAdmin = indexAdmin;
        this.productRepository = productRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>失敗時丟<b>可重試</b>的 {@link BusinessException}（C 系列錯誤碼）。
     * 型別選擇是有後果的：{@code KafkaConsumerConfig} 把 {@code IllegalStateException}
     * 歸為不可重試，用它的話 ES 一次逾時就會讓訊息<b>第一次就進死信</b>，
     * 而那件商品的索引永久停在舊狀態——下架的繼續被搜到、上架的永遠搜不到。
     *
     * <p>方向與 {@link #search} 相反是刻意的：搜尋失敗降級（搜不準沒有後果），
     * 寫入失敗必須重試（漏索引會累積且沒有人會發現）。
     */
    @Override
    public void index(Product product) {
        withAliasSelfHeal(() -> client.index(request -> request
                        .index(ALIAS)
                        // 文件 ID 用商品 ID：寫入是覆寫而非新增，天然冪等。
                        // Outbox 是至少一次語意，重複投遞只是再寫一次同樣的內容
                        .id(String.valueOf(product.id()))
                        .document(ProductDocument.from(product))),
                "寫入搜尋索引失敗 productId=" + product.id());
        // 重建進行中時同一份也寫進新索引，否則這筆變更會在切換 alias 時被丟掉
        String target = rebuildTarget.get();
        if (target != null) {
            withAliasSelfHeal(() -> client.index(request -> request
                            .index(target)
                            .id(String.valueOf(product.id()))
                            .document(ProductDocument.from(product))),
                    "寫入重建中的搜尋索引失敗 productId=" + product.id());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>要移除的東西本來就不在時視為成功——那才是真正的冪等。
     * 把「找不到」當成錯誤，重複投遞的下架事件會不斷進死信。
     */
    @Override
    public void remove(Long productId) {
        try {
            withAliasSelfHeal(() -> client.delete(request -> request
                            .index(ALIAS).id(String.valueOf(productId))),
                    "從搜尋索引移除失敗 productId=" + productId);
        } catch (BusinessException e) {
            if (!isNotFound(e.getCause())) {
                throw e;
            }
            log.debug("商品 {} 本來就不在索引裡，移除視為完成", productId);
        }
        String target = rebuildTarget.get();
        if (target != null) {
            try {
                withAliasSelfHeal(() -> client.delete(request -> request
                                .index(target).id(String.valueOf(productId))),
                        "從重建中的搜尋索引移除失敗 productId=" + productId);
            } catch (BusinessException e) {
                if (!isNotFound(e.getCause())) {
                    throw e;
                }
            }
        }
    }

    /**
     * 執行一次寫入；遇到「索引不存在」就補建 alias 再試一次。
     *
     * <p>自我修復是必要的，因為 ES 預設會把寫入不存在的名稱<b>自動建成實體索引</b>。
     * 一旦 {@code products} 變成實體索引而非 alias，重建索引就永遠失敗，
     * 而那個狀態沒有任何 API 救得回來（實測過，只能人工刪索引）。
     * 先補建 alias 就不會走到那條路。
     */
    private void withAliasSelfHeal(EsCall call, String failureMessage) {
        try {
            call.run();
        } catch (IOException | RuntimeException first) {
            if (isNotFound(first) && indexAdmin.ensureAliasExists()) {
                try {
                    call.run();
                    return;
                } catch (IOException | RuntimeException retried) {
                    throw searchUnavailable(failureMessage, retried);
                }
            }
            throw searchUnavailable(failureMessage, first);
        }
    }

    private static BusinessException searchUnavailable(String message, Throwable cause) {
        BusinessException failure =
                new BusinessException(ErrorCode.SEARCH_INDEX_UNAVAILABLE, message);
        failure.initCause(cause);
        return failure;
    }

    /**
     * ES 的「索引或文件不存在」。
     *
     * <p>比對訊息字串是無奈之舉：這個情況在不同版本與不同呼叫路徑上
     * 會包成不同的例外型別，沒有一個穩定的型別可以攔。
     */
    private static boolean isNotFound(Throwable e) {
        for (Throwable cause = e; cause != null && cause.getCause() != cause;
                cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && (message.contains("index_not_found_exception")
                    || message.contains("404"))) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface EsCall {
        void run() throws IOException;
    }

    @Override
    public ProductSearchResult search(SearchQuery query) {
        try {
            SearchResponse<ProductDocument> response = client.search(request -> request
                            .index(ALIAS)
                            .query(buildQuery(query))
                            .from(query.page() * query.size())
                            .size(query.size())
                            .aggregations(FACET_BRAND, agg -> agg
                                    // brand 主欄位是 text（要能部分比對），
                                    // 分面與精確篩選走它的 keyword 子欄位
                                    .terms(terms -> terms.field("brand.keyword").size(20))),
                    ProductDocument.class);
            return toResult(response);
        } catch (IOException | RuntimeException e) {
            log.warn("搜尋失敗，降級為資料庫查詢 keyword={}", query.keyword(), e);
            return degradedSearch(query);
        }
    }

    /**
     * 關鍵字比對商品名與品牌，商品名權重較高。
     *
     * <p>加權不是隨手填的：使用者打「小米」時，想找的是名字裡有小米的商品，
     * 而不是某個描述裡提到小米的配件。
     *
     * <p>關鍵字為空時退化成 {@code match_all}，讓純用類目或品牌篩選也走同一條路。
     */
    private Query buildQuery(SearchQuery query) {
        return Query.of(q -> q.bool(bool -> {
            if (query.keyword() != null && !query.keyword().isBlank()) {
                bool.must(must -> must.multiMatch(match -> match
                        .query(query.keyword())
                        .fields("name^3", "brand^2", "description")));
            } else {
                bool.must(must -> must.matchAll(all -> all));
            }
            // 篩選條件走 filter 而不是 must：filter 不算分也可被 ES 快取
            if (query.categoryId() != null) {
                bool.filter(filter -> filter.term(term -> term
                        .field("categoryId").value(query.categoryId())));
            }
            if (query.brand() != null && !query.brand().isBlank()) {
                bool.filter(filter -> filter.term(term -> term
                        .field("brand.keyword").value(query.brand())));
            }
            return bool;
        }));
    }

    private static ProductSearchResult toResult(SearchResponse<ProductDocument> response) {
        List<ProductSearchResult.Hit> hits = response.hits().hits().stream()
                .map(hit -> hit.source())
                .filter(doc -> doc != null)
                .map(ProductDocument::toHit)
                .toList();

        Map<String, Long> facets = new LinkedHashMap<>();
        var brandAgg = response.aggregations().get(FACET_BRAND);
        if (brandAgg != null && brandAgg.isSterms()) {
            brandAgg.sterms().buckets().array()
                    .forEach(bucket -> facets.put(bucket.key().stringValue(), bucket.docCount()));
        }

        long total = response.hits().total() == null ? hits.size() : response.hits().total().value();
        return new ProductSearchResult(hits, total, facets, false);
    }

    /**
     * 降級路徑：資料庫的模糊比對。
     *
     * <p><b>沒有相關性排序，也沒有分面。</b> 硬湊一份分面出來只會是錯的，
     * 而錯的統計比沒有統計更糟。回傳空 map 並標記 degraded，
     * 讓前端自己決定要不要顯示那一區。
     */
    private ProductSearchResult degradedSearch(SearchQuery query) {
        try {
            // brand 也要傳下去。先前這裡漏了它，使用者篩了「Apple」卻拿到
            // 一堆別的品牌——降級的承諾是「搜不準」，不是「篩選條件被忽略」
            List<ProductSearchResult.Hit> hits = productRepository
                    .searchByKeyword(query.keyword(), query.categoryId(), query.brand(),
                            query.size(), query.page() * query.size())
                    .stream()
                    .map(product -> new ProductSearchResult.Hit(
                            product.id(), product.name(), product.brand(),
                            product.categoryId(), product.lowestPrice()))
                    .toList();
            return new ProductSearchResult(hits, hits.size(), Map.of(), true);
        } catch (RuntimeException e) {
            // 連資料庫都查不動就真的沒辦法了，但仍然回空結果而不是拋例外——
            // 搜尋壞掉不該讓整個頁面壞掉
            log.error("搜尋降級路徑也失敗", e);
            return ProductSearchResult.empty(true);
        }
    }

    @Override
    public long reindexAll() {
        String target = indexAdmin.createNextVersion();
        // 從這一刻起，消費端的寫入會同時進舊索引與這個新索引
        rebuildTarget.set(target);
        long total = 0;
        int page = 0;
        try {
            while (true) {
                // 依「頁」前進，不是依「上一批實際拿到幾筆」前進。
                //
                // findOnShelf 的簽章收 offset，但它的實作是 offset / limit 換算成頁碼——
                // 所以 offset 必須是 limit 的整數倍才會走到下一頁。
                // 先前用 batch.size() 累加 offset：三筆商品時 offset 從 0 加到 3，
                // 換算回去還是第 0 頁，於是同一批被重寫了 167 次
                // （實測回報 indexed=501，而 ES 裡只有 3 筆）。
                //
                // 重建的來源是資料庫，不是舊索引——
                // 從舊索引複製會把既有的錯誤一起複製過去
                List<Product> batch =
                        productRepository.findOnShelf(null, REINDEX_BATCH, page * REINDEX_BATCH);
                if (batch.isEmpty()) {
                    break;
                }
                List<BulkOperation> operations = new ArrayList<>(batch.size());
                for (Product product : batch) {
                    operations.add(BulkOperation.of(op -> op.index(idx -> idx
                            .index(target)
                            .id(String.valueOf(product.id()))
                            .document(ProductDocument.from(product)))));
                }
                client.bulk(request -> request.operations(operations));
                total += batch.size();
                // 這一批不滿一頁就代表沒有下一頁了，不必再打一次空查詢確認
                if (batch.size() < REINDEX_BATCH) {
                    break;
                }
                page++;
            }
            // 全部寫完才切 alias。中途切換會讓使用者看到一份只索引到一半的結果
            indexAdmin.switchAliasTo(target);
            return total;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("重建搜尋索引失敗，alias 未切換，舊索引仍在服務", e);
        } finally {
            // 無論成功或失敗都要清掉，否則之後每次寫入都會多打一個
            // 已經沒有人在用的索引
            rebuildTarget.set(null);
        }
    }
}
