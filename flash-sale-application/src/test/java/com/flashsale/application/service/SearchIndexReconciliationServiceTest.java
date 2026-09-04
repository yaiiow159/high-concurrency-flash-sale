package com.flashsale.application.service;

import com.flashsale.application.port.in.dto.SearchIndexReconciliation;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.application.port.out.ProductSearchIndex;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.ProductStatus;
import com.flashsale.domain.catalog.Sku;
import com.flashsale.domain.catalog.SkuSpec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 搜尋索引對帳。
 *
 * <h2>要守的是兩件事</h2>
 *
 * <p><b>一、兩個方向的偏差都要抓到。</b>
 * 「在資料庫但不在索引」的症狀是商品搜不到——沒有人會抱怨一個他不知道存在的商品，
 * 所以只能靠對帳發現。「在索引但不在資料庫」的症狀是搜到了買不到。
 * 兩者的成因與嚴重度不同，不能混成一個數字。
 *
 * <p><b>二、修復動作必須重讀當下狀態。</b>
 * 對帳到修復之間可能又有變更；直接照著對帳當時的集合寫，
 * 會把剛下架的商品又寫回索引——那就是自動修復製造出新問題，
 * 也正是 CLAUDE.md 對自動修復抱持警戒的原因。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("搜尋索引對帳")
class SearchIndexReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

    @Mock
    private ProductSearchIndex searchIndex;
    @Mock
    private ProductRepository productRepository;

    private SearchIndexReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new SearchIndexReconciliationService(searchIndex, productRepository,
                new SearchIndexMetrics(new SimpleMeterRegistry()));
    }

    private static Product product(long id, ProductStatus status) {
        Sku sku = Sku.restore(id * 100, id, SkuSpec.of(Map.of("容量", "256G")),
                new BigDecimal("100"), "B-" + id, status);
        return Product.restore(id, 10L, "商品" + id, "品牌", "說明",
                status, List.of(sku), NOW);
    }

    private void given(Set<Long> onShelf, Set<Long> indexed) {
        when(productRepository.findOnShelfIds()).thenReturn(onShelf);
        when(searchIndex.allIndexedIds()).thenReturn(indexed);
    }

    @Nested
    @DisplayName("偵測")
    class Detection {

        @Test
        @DisplayName("兩邊一致時判定帳平，不做任何寫入")
        void balancedWhenIdentical() {
            given(Set.of(1L, 2L), Set.of(1L, 2L));

            SearchIndexReconciliation result = service.reconcile(true);

            assertThat(result.balanced()).isTrue();
            verify(searchIndex, never()).index(any());
            verify(searchIndex, never()).remove(anyLong());
        }

        @Test
        @DisplayName("在資料庫但不在索引 → missing（症狀：商品搜不到）")
        void detectsMissing() {
            given(Set.of(1L, 2L), Set.of(1L));

            SearchIndexReconciliation result = service.reconcile(false);

            assertThat(result.missing()).containsExactly(2L);
            assertThat(result.orphaned()).isEmpty();
            assertThat(result.balanced()).isFalse();
        }

        @Test
        @DisplayName("在索引但不是上架 → orphaned（症狀：搜到了但買不到）")
        void detectsOrphaned() {
            given(Set.of(1L), Set.of(1L, 99L));

            SearchIndexReconciliation result = service.reconcile(false);

            assertThat(result.orphaned()).containsExactly(99L);
            assertThat(result.missing()).isEmpty();
        }

        @Test
        @DisplayName("兩個方向分開回報——成因與嚴重度不同，混成一個數字就看不出該做什麼")
        void reportsBothDirectionsSeparately() {
            given(Set.of(1L, 2L), Set.of(1L, 99L));

            SearchIndexReconciliation result = service.reconcile(false);

            assertThat(result.missing()).containsExactly(2L);
            assertThat(result.orphaned()).containsExactly(99L);
        }

        @Test
        @DisplayName("repair=false 時只回報不寫入")
        void reportOnlyDoesNotWrite() {
            given(Set.of(1L, 2L), Set.of(1L, 99L));

            service.reconcile(false);

            verify(searchIndex, never()).index(any());
            verify(searchIndex, never()).remove(anyLong());
        }
    }

    @Nested
    @DisplayName("自動修復")
    class AutoRepair {

        @Test
        @DisplayName("缺少的補寫進索引")
        void repairsMissing() {
            given(Set.of(1L, 2L), Set.of(1L));
            when(productRepository.findById(2L))
                    .thenReturn(Optional.of(product(2L, ProductStatus.ON_SHELF)));

            SearchIndexReconciliation result = service.reconcile(true);

            verify(searchIndex).index(any());
            assertThat(result.repaired()).isEqualTo(1);
        }

        @Test
        @DisplayName("多出來的從索引移除")
        void repairsOrphaned() {
            given(Set.of(1L), Set.of(1L, 99L));
            when(productRepository.findById(99L))
                    .thenReturn(Optional.of(product(99L, ProductStatus.OFF_SHELF)));

            service.reconcile(true);

            verify(searchIndex).remove(99L);
        }

        @Test
        @DisplayName("修復時重讀當下狀態——對帳到修復之間又下架的商品不可被寫回索引")
        void rereadsCurrentStateBeforeRepairing() {
            // 對帳當下 2 是上架的、被判為 missing；但修復前它已經下架了
            given(Set.of(1L, 2L), Set.of(1L));
            when(productRepository.findById(2L))
                    .thenReturn(Optional.of(product(2L, ProductStatus.OFF_SHELF)));

            service.reconcile(true);

            // 照著對帳當時的集合寫的話，這裡會是 index()——
            // 那就是自動修復自己製造出一個新的錯誤
            verify(searchIndex).remove(2L);
            verify(searchIndex, never()).index(any());
        }

        @Test
        @DisplayName("商品已不存在時從索引移除，不留下指向不存在商品的殘骸")
        void removesVanishedProduct() {
            given(Set.of(), Set.of(99L));
            when(productRepository.findById(99L)).thenReturn(Optional.empty());

            service.reconcile(true);

            verify(searchIndex).remove(99L);
        }

        @Test
        @DisplayName("單筆修復失敗不中斷整批——一筆修不掉不該讓其他商品也繼續搜不到")
        void oneFailureDoesNotStopTheRest() {
            given(Set.of(1L, 2L, 3L), Set.of());
            when(productRepository.findById(anyLong()))
                    .thenAnswer(call -> Optional.of(
                            product(call.getArgument(0), ProductStatus.ON_SHELF)));
            // 中間那筆炸掉
            org.mockito.Mockito.doThrow(new RuntimeException("ES 掛了"))
                    .doNothing()
                    .doNothing()
                    .when(searchIndex).index(any());

            SearchIndexReconciliation result = service.reconcile(true);

            // 三筆裡兩筆修好；沒修掉的那筆下一輪會再被抓到
            assertThat(result.repaired()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("讀不到索引時")
    class IndexUnavailable {

        @Test
        @DisplayName("往上拋而不是當成空索引——空集合會觸發全量重寫")
        void doesNotTreatFailureAsEmptyIndex() {
            when(productRepository.findOnShelfIds()).thenReturn(Set.of(1L, 2L, 3L));
            when(searchIndex.allIndexedIds()).thenThrow(new RuntimeException("ES 掛了"));

            // 若這裡把失敗當成「索引是空的」，就會判定三筆全部 missing
            // 並逐筆重寫——把一次連線失敗放大成一次全量重建
            assertThatThrownBy(() -> service.reconcile(true))
                    .isInstanceOf(RuntimeException.class);
            verify(searchIndex, never()).index(any());
        }
    }
}
