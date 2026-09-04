package com.flashsale.application.service;

import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.application.port.out.ProductSearchIndex;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.ProductStatus;
import com.flashsale.domain.catalog.Sku;
import com.flashsale.domain.catalog.SkuSpec;
import com.flashsale.domain.catalog.event.ProductIndexChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品搜尋服務。
 *
 * <h2>要守的是「索引與資料庫不要分岔」</h2>
 *
 * <p>索引是一份會落後的副本，而它與資料庫分岔時<b>沒有任何東西會報錯</b>——
 * 搜尋照樣回結果，只是結果是錯的。使用者搜到一個已下架的商品、點進去發現
 * 買不到，那是最難追的一種問題，因為系統從頭到尾都沒有異常。
 *
 * <p>因此這裡逐一釘住「什麼狀態的商品該在索引裡、什麼不該」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("商品搜尋服務")
class ProductSearchServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");
    private static final long PRODUCT_ID = 1L;

    @Mock
    private ProductSearchIndex searchIndex;
    @Mock
    private ProductRepository productRepository;

    private ProductSearchService service;

    @BeforeEach
    void setUp() {
        service = new ProductSearchService(searchIndex, productRepository);
    }

    /** 商品至少要有一個 SKU——那是聚合根的不變式，不是測試的方便問題。 */
    private static Product product(ProductStatus status) {
        Sku sku = Sku.restore(100L, PRODUCT_ID, SkuSpec.of(Map.of("容量", "256G")),
                new BigDecimal("29900"), "BARCODE-1", status);
        return Product.restore(PRODUCT_ID, 10L, "測試商品", "測試品牌", "說明",
                status, List.of(sku), NOW);
    }

    private static ProductIndexChangedEvent event() {
        return new ProductIndexChangedEvent("evt-1", PRODUCT_ID, NOW);
    }

    @Nested
    @DisplayName("索引同步")
    class IndexSync {

        @Test
        @DisplayName("上架的商品寫進索引")
        void onShelfIsIndexed() {
            when(productRepository.findById(PRODUCT_ID))
                    .thenReturn(Optional.of(product(ProductStatus.ON_SHELF)));

            service.applyIndexChange(event());

            verify(searchIndex).index(any());
            verify(searchIndex, never()).remove(any());
        }

        @Test
        @DisplayName("下架的商品從索引移除——留著會讓使用者搜到買不到的東西")
        void offShelfIsRemoved() {
            when(productRepository.findById(PRODUCT_ID))
                    .thenReturn(Optional.of(product(ProductStatus.OFF_SHELF)));

            service.applyIndexChange(event());

            verify(searchIndex).remove(PRODUCT_ID);
            verify(searchIndex, never()).index(any());
        }

        @Test
        @DisplayName("草稿也不進索引——它還沒對外，被搜到就是提前曝光")
        void draftIsRemoved() {
            when(productRepository.findById(PRODUCT_ID))
                    .thenReturn(Optional.of(product(ProductStatus.DRAFT)));

            service.applyIndexChange(event());

            verify(searchIndex).remove(PRODUCT_ID);
        }

        @Test
        @DisplayName("商品查不到時從索引移除，不留下指向不存在商品的殘骸")
        void missingProductIsRemoved() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

            service.applyIndexChange(event());

            verify(searchIndex).remove(PRODUCT_ID);
            verify(searchIndex, never()).index(any());
        }

        @Test
        @DisplayName("重複投遞同一個事件是安全的——寫入以商品 ID 為文件 ID，是覆寫不是新增")
        void duplicateDeliveryIsIdempotent() {
            when(productRepository.findById(PRODUCT_ID))
                    .thenReturn(Optional.of(product(ProductStatus.ON_SHELF)));

            service.applyIndexChange(event());
            service.applyIndexChange(event());

            // 兩次都寫，但因為是覆寫，索引狀態與寫一次相同。
            // 這條釘住的是「不可以為了省事而加一層去重表」——那才是要維護的東西
            verify(searchIndex, org.mockito.Mockito.times(2)).index(any());
        }
    }

    @Nested
    @DisplayName("查詢參數")
    class QueryParameters {

        @Test
        @DisplayName("頁大小夾在上限——這是公開端點，任何人都能無限次呼叫")
        void clampsPageSize() {
            service.search("手機", null, null, 0, 99999);

            ArgumentCaptor<ProductSearchIndex.SearchQuery> captor =
                    ArgumentCaptor.forClass(ProductSearchIndex.SearchQuery.class);
            verify(searchIndex).search(captor.capture());
            assertThat(captor.getValue().size()).isEqualTo(50);
        }

        @Test
        @DisplayName("負的頁碼夾成 0，而不是算出一個負的 offset")
        void clampsNegativePage() {
            service.search("手機", null, null, -5, 20);

            ArgumentCaptor<ProductSearchIndex.SearchQuery> captor =
                    ArgumentCaptor.forClass(ProductSearchIndex.SearchQuery.class);
            verify(searchIndex).search(captor.capture());
            assertThat(captor.getValue().page()).isZero();
        }

        @Test
        @DisplayName("null 關鍵字轉成空字串——讓純分類瀏覽走同一條路而不是另一個分支")
        void nullKeywordBecomesEmpty() {
            service.search(null, 10L, null, 0, 20);

            ArgumentCaptor<ProductSearchIndex.SearchQuery> captor =
                    ArgumentCaptor.forClass(ProductSearchIndex.SearchQuery.class);
            verify(searchIndex).search(captor.capture());
            assertThat(captor.getValue().keyword()).isEmpty();
        }

        @Test
        @DisplayName("關鍵字前後空白去掉——「 手機 」與「手機」是同一個查詢")
        void trimsKeyword() {
            service.search("  手機  ", null, null, 0, 20);

            ArgumentCaptor<ProductSearchIndex.SearchQuery> captor =
                    ArgumentCaptor.forClass(ProductSearchIndex.SearchQuery.class);
            verify(searchIndex).search(captor.capture());
            assertThat(captor.getValue().keyword()).isEqualTo("手機");
        }
    }
}
