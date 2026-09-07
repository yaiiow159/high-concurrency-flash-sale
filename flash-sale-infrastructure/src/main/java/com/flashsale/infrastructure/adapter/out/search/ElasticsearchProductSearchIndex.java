package com.flashsale.infrastructure.adapter.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.flashsale.application.port.in.dto.ProductSearchResult;
import com.flashsale.application.port.out.InventoryRepository;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.application.port.out.ProductSearchIndex;
import com.flashsale.application.port.out.ProductSearchIndex.SearchSort;
import com.flashsale.application.port.out.ReviewRepository;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.Sku;
import com.flashsale.domain.inventory.Inventory;
import com.flashsale.domain.review.ProductRating;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Elasticsearch 商品索引（ADR-0012）。 */
@Component
public class ElasticsearchProductSearchIndex implements ProductSearchIndex {

    private static final Logger log =
            LoggerFactory.getLogger(ElasticsearchProductSearchIndex.class);

    /** 對外的固定名稱。實際索引是 {@code products_vN}，靠 alias 指過去。 */
    static final String ALIAS = "products";

    /** 重建時一次搬多少筆。太大會讓單一 bulk 請求逾時，太小則往返次數多。 */
    private static final int REINDEX_BATCH = 500;

    private static final String FACET_BRAND = "brand";

    /** 對帳掃描的單批筆數。 */
    private static final int SCAN_BATCH = 1000;

    /** 重建進行中的目標索引名；沒有重建時為 {@code null}。 */
    private final AtomicReference<String> rebuildTarget = new AtomicReference<>();

    /** 重建後保留幾代舊索引。 */
    private static final int KEEP_GENERATIONS = 1;

    private final ElasticsearchClient client;
    private final ProductIndexAdmin indexAdmin;
    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final InventoryRepository inventoryRepository;

    // 評分與庫存直接向持久化埠取：搜尋文件是投影，組裝它是這個轉接器自己的事，
    // 應用層不該為了一份索引去認識「文件長什麼樣」
    public ElasticsearchProductSearchIndex(ElasticsearchClient client,
                                           ProductIndexAdmin indexAdmin,
                                           ProductRepository productRepository,
                                           ReviewRepository reviewRepository,
                                           InventoryRepository inventoryRepository) {
        this.client = client;
        this.indexAdmin = indexAdmin;
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.inventoryRepository = inventoryRepository;
    }

    /** 一次替一批商品組文件：兩次批次查詢，不是每件商品各查兩次。 */
    private List<ProductDocument> assemble(List<Product> products) {
        List<Long> productIds = products.stream().map(Product::id).toList();
        List<Long> skuIds = products.stream()
                .flatMap(product -> product.skus().stream()).map(Sku::id).toList();
        Map<Long, ProductRating> ratings = reviewRepository.findRatings(productIds);
        Map<Long, Integer> available = new HashMap<>();
        for (Inventory inventory : inventoryRepository.findBySkuIds(skuIds)) {
            available.put(inventory.skuId(), inventory.available());
        }
        List<ProductDocument> documents = new ArrayList<>(products.size());
        for (Product product : products) {
            // 任一可買的 SKU 還有量就算有貨——使用者問的是「這個商品買不買得到」
            boolean inStock = product.skus().stream()
                    .anyMatch(sku -> sku.isPurchasable() && available.getOrDefault(sku.id(), 0) > 0);
            documents.add(ProductDocument.from(product,
                    ratings.getOrDefault(product.id(), ProductRating.empty(product.id())), inStock));
        }
        return documents;
    }

    /** {@inheritDoc} */
    @Override
    public void index(Product product) {
        ProductDocument document = assemble(List.of(product)).get(0);
        withAliasSelfHeal(() -> client.index(request -> request
                        .index(ALIAS)
                        // 文件 ID 用商品 ID：寫入是覆寫而非新增，天然冪等。
                        // Outbox 是至少一次語意，重複投遞只是再寫一次同樣的內容
                        .id(String.valueOf(product.id()))
                        .document(document)),
                "寫入搜尋索引失敗 productId=" + product.id());
        // 重建進行中時同一份也寫進新索引，否則這筆變更會在切換 alias 時被丟掉
        String target = rebuildTarget.get();
        if (target != null) {
            withAliasSelfHeal(() -> client.index(request -> request
                            .index(target)
                            .id(String.valueOf(product.id()))
                            .document(document)),
                    "寫入重建中的搜尋索引失敗 productId=" + product.id());
        }
    }

    /** {@inheritDoc} */
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

    /** 執行一次寫入；遇到「索引不存在」就補建 alias 再試一次。 */
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

    /** ES 的「索引或文件不存在」。 */
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

    /** {@inheritDoc} */
    @Override
    public Set<Long> allIndexedIds() {
        Set<Long> ids = new HashSet<>();
        List<FieldValue> cursor = List.of();
        try {
            while (true) {
                final List<FieldValue> after = cursor;
                SearchResponse<Void> response = client.search(request -> {
                    request.index(ALIAS)
                            .query(q -> q.matchAll(all -> all))
                            .source(source -> source.fetch(false))
                            .sort(sort -> sort.field(f -> f.field("productId").order(SortOrder.Asc)))
                            .size(SCAN_BATCH);
                    // 第一批沒有游標。這裡必須「不呼叫」而不是「傳 null」——
                    // 客戶端的 searchAfter 對 null 直接丟 NPE
                    if (!after.isEmpty()) {
                        request.searchAfter(after);
                    }
                    return request;
                }, Void.class);

                var hits = response.hits().hits();
                if (hits.isEmpty()) {
                    return ids;
                }
                hits.forEach(hit -> ids.add(Long.valueOf(hit.id())));
                cursor = hits.get(hits.size() - 1).sort();
            }
        } catch (IOException | RuntimeException e) {
            // 讀不到索引就沒辦法對帳。往上拋而不是回空集合——
            // 空集合會被解讀成「索引整份不見了」而觸發全量修復，
            // 那是把一次連線失敗放大成一次全量重寫
            throw searchUnavailable("讀取搜尋索引的文件 ID 失敗", e);
        }
    }

    /** 搜尋建議。 */
    @Override
    public List<String> suggest(String prefix, int limit) {
        String trimmed = prefix == null ? "" : prefix.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        try {
            SearchResponse<ProductDocument> response = client.search(request -> request
                            .index(ALIAS)
                            // 多取一些再去重——同一個品牌會有很多商品，
                            // 只取 limit 筆的話去重後可能只剩一兩個
                            .size(limit * 5)
                            .query(query -> query.bool(bool -> bool
                                    .should(should -> should.matchPhrasePrefix(
                                            match -> match.field("name").query(trimmed)))
                                    .should(should -> should.matchPhrasePrefix(
                                            match -> match.field("brand").query(trimmed)))
                                    .minimumShouldMatch("1"))),
                    ProductDocument.class);

            return response.hits().hits().stream()
                    .map(Hit::source)
                    .filter(java.util.Objects::nonNull)
                    .flatMap(doc -> Stream.of(doc.name(), doc.brand()))
                    .filter(java.util.Objects::nonNull)
                    .filter(candidate -> candidate.toLowerCase()
                            .contains(trimmed.toLowerCase()))
                    .distinct()
                    .limit(limit)
                    .toList();
        } catch (Exception unavailable) {
            log.warn("搜尋建議失敗，回空清單 prefix={}", trimmed, unavailable);
            return List.of();
        }
    }

    @Override
    public ProductSearchResult search(SearchQuery query) {
        try {
            SearchResponse<ProductDocument> response = client.search(request -> request
                            .index(ALIAS)
                            .query(buildQuery(query))
                            .sort(sortOf(query))
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

    /** 關鍵字比對商品名與品牌，商品名權重較高。 */
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
            if (query.minPrice() != null || query.maxPrice() != null) {
                bool.filter(filter -> filter.range(range -> {
                    range.field("lowestPrice");
                    if (query.minPrice() != null) {
                        range.gte(co.elastic.clients.json.JsonData.of(query.minPrice()));
                    }
                    if (query.maxPrice() != null) {
                        range.lte(co.elastic.clients.json.JsonData.of(query.maxPrice()));
                    }
                    return range;
                }));
            }
            if (query.minRating() != null) {
                bool.filter(filter -> filter.range(range -> range
                        .field("ratingAverage")
                        .gte(co.elastic.clients.json.JsonData.of(query.minRating()))));
            }
            if (query.inStockOnly()) {
                bool.filter(filter -> filter.term(term -> term.field("inStock").value(true)));
            }
            return bool;
        }));
    }

    /**
     * 排序。沒有關鍵字時「相關性」沒有意義（全部同分），退化成最新上架，
     * 否則使用者看到的順序等於文件寫入順序——看起來像壞掉。
     */
    private static List<SortOptions> sortOf(SearchQuery query) {
        SearchSort sort = query.sort() == null ? SearchSort.RELEVANCE : query.sort();
        boolean hasKeyword = query.keyword() != null && !query.keyword().isBlank();
        if (sort == SearchSort.RELEVANCE && !hasKeyword) {
            sort = SearchSort.NEWEST;
        }
        return switch (sort) {
            case RELEVANCE -> List.of(SortOptions.of(o -> o.score(sc -> sc.order(SortOrder.Desc))));
            case PRICE_ASC -> List.of(field("lowestPrice", SortOrder.Asc));
            case PRICE_DESC -> List.of(field("lowestPrice", SortOrder.Desc));
            // 同分時筆數多的在前：一則五星不該贏過一百則四點九星
            case RATING -> List.of(field("ratingAverage", SortOrder.Desc),
                    field("ratingCount", SortOrder.Desc));
            case NEWEST -> List.of(field("createdAt", SortOrder.Desc));
        };
    }

    private static SortOptions field(String name, SortOrder order) {
        return SortOptions.of(o -> o.field(f -> f.field(name).order(order)));
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

    /** 降級路徑：資料庫的模糊比對。 */
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
                            product.categoryId(), product.lowestPrice(),
                            java.math.BigDecimal.ZERO, 0, true))
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
                for (ProductDocument document : assemble(batch)) {
                    operations.add(BulkOperation.of(op -> op.index(idx -> idx
                            .index(target)
                            .id(String.valueOf(document.productId()))
                            .document(document))));
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

            // 切完才清舊索引。**順序不可調換**：先清再切的話，
            // 中間若切換失敗，就會變成「新索引還沒生效、舊索引已經沒了」——
            // 而那是唯一一種會讓搜尋完全消失的組合。
            //
            // 保留一代供回退，那正是 switchAliasTo 保留舊索引的理由。
            indexAdmin.pruneOldVersions(KEEP_GENERATIONS);
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
