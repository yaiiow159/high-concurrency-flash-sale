package com.flashsale.infrastructure.adapter.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.flashsale.application.port.in.dto.ProductSearchResult;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.application.port.out.ProductSearchIndex;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.ProductStatus;
import com.flashsale.domain.catalog.Sku;
import com.flashsale.domain.catalog.SkuSpec;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Elasticsearch 商品索引。
 *
 * <h2>兩件實際壞過的事</h2>
 *
 * <p><b>一、重建索引的分頁。</b> {@code findOnShelf} 的簽章收 offset，
 * 實作卻是 {@code offset / limit} 換算頁碼。原本用「上一批實際拿到幾筆」
 * 累加 offset，三筆商品時 offset 從 0 加到 3、換算回去還是第 0 頁，
 * 於是同一批被重寫了 167 次——實機回報 indexed=501 而 ES 裡只有 3 筆。
 *
 * <p><b>二、搜尋失敗必須降級而不是往上拋</b>（ADR-0012 決策 4）：
 * 搜不準不會產生任何錯誤資料，讓整頁壞掉換不到任何東西。
 * 而索引<b>寫入</b>失敗方向相反——必須往上拋讓 MQ 重試，
 * 漏索引會累積成「商品搜不到而沒有人發現」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Elasticsearch 商品索引")
class ElasticsearchProductSearchIndexTest {

    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

    @Mock
    private ElasticsearchClient client;
    @Mock
    private ProductIndexAdmin indexAdmin;
    @Mock
    private ProductRepository productRepository;

    private ElasticsearchProductSearchIndex index;

    @BeforeEach
    void setUp() {
        index = new ElasticsearchProductSearchIndex(client, indexAdmin, productRepository);
        when(indexAdmin.createNextVersion()).thenReturn("products_v1");
    }

    private static Product product(long id) {
        Sku sku = Sku.restore(id * 100, id, SkuSpec.of(Map.of("容量", "256G")),
                new BigDecimal("100"), "B-" + id, ProductStatus.ON_SHELF);
        return Product.restore(id, 10L, "商品" + id, "品牌", "說明",
                ProductStatus.ON_SHELF, List.of(sku), NOW);
    }

    @Nested
    @DisplayName("重建索引")
    class Reindex {

        /** 記下每次查詢用的 offset，用來證明分頁真的有前進。 */
        private List<Integer> recordOffsets(int totalProducts) {
            List<Integer> offsets = new ArrayList<>();
            when(productRepository.findOnShelf(any(), anyInt(), anyInt())).thenAnswer(call -> {
                int limit = call.getArgument(1);
                int offset = call.getArgument(2);
                offsets.add(offset);
                if (offset >= totalProducts) {
                    return List.of();
                }
                return IntStream.range(offset, Math.min(offset + limit, totalProducts))
                        .mapToObj(n -> product(n + 1L))
                        .toList();
            });
            return offsets;
        }

        @Test
        @DisplayName("回報的筆數等於實際商品數——先前同一批被重複計數 167 次")
        void reportsActualProductCount() {
            recordOffsets(3);

            long indexed = index.reindexAll();

            assertThat(indexed)
                    .as("三個商品就是三筆。回報 501 代表同一頁被重讀了 167 次")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("不滿一頁就停，不會再打一次空查詢")
        void stopsOnPartialPage() {
            List<Integer> offsets = recordOffsets(3);

            index.reindexAll();

            assertThat(offsets).containsExactly(0);
        }

        @Test
        @DisplayName("超過一頁時 offset 依頁大小前進，而不是依上一批的實際筆數")
        void advancesByPageSize() {
            List<Integer> offsets = recordOffsets(1200);

            long indexed = index.reindexAll();

            assertThat(offsets).containsExactly(0, 500, 1000);
            assertThat(indexed).isEqualTo(1200);
        }

        @Test
        @DisplayName("全部寫完才切 alias——中途切換會讓使用者看到只索引到一半的結果")
        void switchesAliasOnlyAfterAllWritten() {
            recordOffsets(3);

            index.reindexAll();

            verify(indexAdmin).switchAliasTo("products_v1");
        }

        @Test
        @DisplayName("寫入失敗時不切 alias，舊索引繼續服務")
        void keepsOldAliasOnFailure() throws Exception {
            recordOffsets(3);
            when(client.bulk(any(Function.class))).thenThrow(new RuntimeException("ES 掛了"));

            assertThatThrownBy(() -> index.reindexAll())
                    .isInstanceOf(IllegalStateException.class);

            verify(indexAdmin, never()).switchAliasTo(anyString());
        }

        @Test
        @DisplayName("先切 alias 再清舊索引——順序反了會讓搜尋完全消失")
        void prunesOnlyAfterAliasSwitch() {
            recordOffsets(1);

            index.reindexAll();

            // 先清再切的話，中間若切換失敗就變成「新索引還沒生效、舊索引已經沒了」，
            // 而那是唯一一種會讓搜尋完全消失的組合
            InOrder order = inOrder(indexAdmin);
            order.verify(indexAdmin).switchAliasTo("products_v1");
            order.verify(indexAdmin).pruneOldVersions(anyInt());
        }

        @Test
        @DisplayName("重建失敗時不清舊索引——舊的還在服務")
        void doesNotPruneWhenRebuildFails() {
            when(productRepository.findOnShelf(any(), anyInt(), anyInt()))
                    .thenThrow(new IllegalStateException("資料庫掛了"));

            assertThatThrownBy(() -> index.reindexAll())
                    .isInstanceOf(IllegalStateException.class);

            verify(indexAdmin, never()).pruneOldVersions(anyInt());
        }
    }

    @Nested
    @DisplayName("故障時的方向")
    class FailureDirection {

        @Test
        @DisplayName("搜尋失敗降級回資料庫，不往上拋——讓整頁壞掉換不到任何東西")
        void searchDegradesInsteadOfThrowing() throws Exception {
            when(client.search(any(Function.class), any()))
                    .thenThrow(new RuntimeException("ES 掛了"));
            when(productRepository.searchByKeyword(anyString(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(List.of(product(1L)));

            ProductSearchResult result = index.search(
                    new ProductSearchIndex.SearchQuery("商品", null, null, 0, 20));

            assertThat(result.degraded()).isTrue();
            assertThat(result.hits()).hasSize(1);
            assertThat(result.facets())
                    .as("降級路徑不做分面，硬湊一份出來只會是錯的")
                    .isEmpty();
        }

        @Test
        @DisplayName("連資料庫都查不動仍然回空結果，不拋例外")
        void bothPathsFailingStillReturnsEmpty() throws Exception {
            when(client.search(any(Function.class), any()))
                    .thenThrow(new RuntimeException("ES 掛了"));
            when(productRepository.searchByKeyword(anyString(), any(), any(), anyInt(), anyInt()))
                    .thenThrow(new RuntimeException("DB 也掛了"));

            ProductSearchResult result = index.search(
                    new ProductSearchIndex.SearchQuery("商品", null, null, 0, 20));

            assertThat(result.hits()).isEmpty();
            assertThat(result.degraded()).isTrue();
        }

        @Test
        @DisplayName("索引寫入失敗丟可重試的錯誤碼——不可重試的話第一次就進死信")
        void indexWriteFailureIsRetryable() throws Exception {
            when(client.index(any(Function.class))).thenThrow(new RuntimeException("ES 掛了"));

            assertThatThrownBy(() -> index.index(product(1L)))
                    .as("吞掉的話商品搜不到，而沒有任何人會發現")
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SEARCH_INDEX_UNAVAILABLE);
            // C 系列＝可重試。KafkaConsumerConfig 讀的就是這個旗標，
            // 而先前丟 IllegalStateException 會被歸為不可重試、第一次就進死信
            assertThat(ErrorCode.SEARCH_INDEX_UNAVAILABLE.retryable()).isTrue();
        }

        @Test
        @DisplayName("索引不存在時先補建 alias 再重試——否則 ES 會把 alias 名稱自動建成實體索引")
        void missingIndexTriggersAliasSelfHeal() throws Exception {
            when(client.index(any(Function.class)))
                    .thenThrow(new RuntimeException("index_not_found_exception"))
                    .thenReturn(null);
            when(indexAdmin.ensureAliasExists()).thenReturn(true);

            index.index(product(1L));

            verify(indexAdmin).ensureAliasExists();
            verify(client, org.mockito.Mockito.times(2)).index(any(Function.class));
        }

        @Test
        @DisplayName("移除一筆本來就不在的文件視為完成——那才是真正的冪等")
        void removingMissingDocumentSucceeds() throws Exception {
            when(client.delete(any(Function.class)))
                    .thenThrow(new RuntimeException("index_not_found_exception"));
            when(indexAdmin.ensureAliasExists()).thenReturn(false);

            // 重複投遞的下架事件會走到這裡。當成錯誤的話它會不斷進死信
            index.remove(1L);
        }
    }
}
