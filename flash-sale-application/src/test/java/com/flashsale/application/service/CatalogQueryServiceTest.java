package com.flashsale.application.service;

import com.flashsale.application.port.in.dto.ProductPage;
import com.flashsale.application.port.out.CategoryRepository;
import com.flashsale.application.port.out.InventoryRepository;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.domain.catalog.Category;
import com.flashsale.domain.catalog.ProductStatus;
import com.flashsale.domain.catalog.ProductSummary;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商店的商品列表：keyset 分頁（ADR-0021）與類目子樹（ADR-0022）。
 *
 * <p>這裡壞掉的方式都很安靜：游標算錯會讓最後一筆重複或漏掉一筆，
 * 子樹沒展開會讓中間層類目回空頁面——兩者都不會拋任何例外。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("商品目錄查詢")
class CatalogQueryServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    private CatalogQueryService service;

    @BeforeEach
    void setUp() {
        service = new CatalogQueryService(productRepository, categoryRepository, inventoryRepository);
    }

    /**
     * <pre>
     * 1 3C
     * └── 2 手機
     *     └── 3 旗艦
     * </pre>
     */
    private void givenTree() {
        Category root = Category.root(1L, "3C", 0);
        Category phone = Category.child(2L, root, "手機", 0);
        when(categoryRepository.findAll())
                .thenReturn(List.of(root, phone, Category.child(3L, phone, "旗艦", 0)));
    }

    private static List<ProductSummary> summaries(int count, long startId) {
        return IntStream.range(0, count)
                .mapToObj(i -> new ProductSummary(startId - i, 3L, "商品 " + i, "牌",
                        ProductStatus.ON_SHELF, new BigDecimal("100"), String.valueOf(startId - i)))
                .toList();
    }

    @Nested
    @DisplayName("keyset 分頁")
    class Keyset {

        @Test
        @DisplayName("多取一筆判斷還有沒有下一頁，回傳時要砍掉")
        void fetchesOneExtraAndTrimsIt() {
            // 要 20 筆，倉庫回 21 筆代表還有下一頁
            when(productRepository.findOnShelfSummaries(any(), any(), any(), any(), eq(21)))
                    .thenReturn(summaries(21, 1000L));

            ProductPage page = service.listProducts(null, null, null, null, null, 20);

            // 多出來那一筆不可以出現在結果裡，否則每頁都會多一件商品
            assertThat(page.items()).hasSize(20);
            assertThat(page.hasMore()).isTrue();
        }

        @Test
        @DisplayName("游標是這一頁最後一筆的 ID，不是多取那一筆的")
        void cursorIsLastItemOfThePage() {
            // id 從 1000 遞減，第 20 筆是 981，多取的第 21 筆是 980。
            // 若誤用 980 當游標，下一頁的 id < 980 會**跳過 980 那件商品**
            when(productRepository.findOnShelfSummaries(any(), any(), any(), any(), eq(21)))
                    .thenReturn(summaries(21, 1000L));

            assertThat(service.listProducts(null, null, null, null, null, 20).nextCursor()).isEqualTo("981");
        }

        @Test
        @DisplayName("剛好一頁時沒有下一頁，游標為 null")
        void exactlyOnePageHasNoCursor() {
            when(productRepository.findOnShelfSummaries(any(), any(), any(), any(), eq(21)))
                    .thenReturn(summaries(20, 1000L));

            ProductPage page = service.listProducts(null, null, null, null, null, 20);

            assertThat(page.items()).hasSize(20);
            assertThat(page.hasMore()).isFalse();
            assertThat(page.nextCursor()).isNull();
        }

        @Test
        @DisplayName("沒有結果時回空頁，而不是帶著游標的空清單")
        void emptyPage() {
            when(productRepository.findOnShelfSummaries(any(), any(), any(), any(), any(Integer.class)))
                    .thenReturn(List.of());

            ProductPage page = service.listProducts(null, null, null, null, null, 20);

            assertThat(page.items()).isEmpty();
            assertThat(page.hasMore()).isFalse();
            assertThat(page.nextCursor()).isNull();
        }

        @Test
        @DisplayName("游標原樣傳給倉庫")
        void passesCursorThrough() {
            when(productRepository.findOnShelfSummaries(any(), any(), any(), any(), any(Integer.class)))
                    .thenReturn(List.of());

            service.listProducts(null, null, "555", null, null, 20);

            verify(productRepository).findOnShelfSummaries(isNull(), any(), any(), any(), eq(21));
        }

        @Test
        @DisplayName("頁大小有上限，否則任何人都能用 size=1000000 掃全表")
        void pageSizeIsCapped() {
            when(productRepository.findOnShelfSummaries(any(), any(), any(), any(), any(Integer.class)))
                    .thenReturn(List.of());

            service.listProducts(null, null, null, null, null, 1_000_000);

            // 上限 100，加上多取的那一筆
            verify(productRepository).findOnShelfSummaries(isNull(), any(), isNull(), any(), eq(101));
        }
    }

    @Nested
    @DisplayName("類目子樹")
    class Subtree {

        @SuppressWarnings("unchecked")
        private Collection<Long> capturedCategoryIds() {
            ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(productRepository).findOnShelfSummaries(captor.capture(), any(), any(), any(), any(Integer.class));
            return captor.getValue();
        }

        @Test
        @DisplayName("點中間層要帶出它底下的葉節點——商品只掛在葉節點上")
        void middleLevelExpandsToLeaves() {
            givenTree();
            when(productRepository.findOnShelfSummaries(any(), any(), any(), any(), any(Integer.class)))
                    .thenReturn(List.of());

            service.listProducts(2L, null, null, null, null, 20);

            assertThat(capturedCategoryIds()).containsExactlyInAnyOrder(2L, 3L);
        }

        @Test
        @DisplayName("子樹涵蓋整棵樹時不下條件——那個 in (...) 會踩中 filesort 懸崖")
        void wholeTreeMeansNoFilter() {
            givenTree();
            when(productRepository.findOnShelfSummaries(any(), any(), any(), any(), any(Integer.class)))
                    .thenReturn(List.of());

            service.listProducts(1L, null, null, null, null, 20);

            // 點根類目等於「全部商品」，帶著每一個類目 ID 的條件毫無作用卻很貴
            assertThat(capturedCategoryIds()).isNull();
        }

        @Test
        @DisplayName("沒指定類目時完全不查類目表")
        void noCategoryMeansNoTreeLookup() {
            when(productRepository.findOnShelfSummaries(any(), any(), any(), any(), any(Integer.class)))
                    .thenReturn(List.of());

            service.listProducts(null, null, null, null, null, 20);

            verify(categoryRepository, org.mockito.Mockito.never()).findAll();
        }

        @Test
        @DisplayName("不存在的類目篩出空結果，不可退化成「顯示全部」")
        void unknownCategoryDoesNotBecomeUnfiltered() {
            givenTree();
            when(productRepository.findOnShelfSummaries(any(), any(), any(), any(), any(Integer.class)))
                    .thenReturn(List.of());

            service.listProducts(999L, null, null, null, null, 20);

            // null 代表不篩選——一個打錯的類目 ID 絕不能變成「全部商品」
            assertThat(capturedCategoryIds()).isNotNull().containsExactly(999L);
        }
    }

    @Nested
    @DisplayName("回傳內容")
    class Mapping {

        @Test
        @DisplayName("列表不帶 SKU 清單，但要有最低價")
        void summaryShape() {
            List<ProductSummary> rows = new ArrayList<>(summaries(1, 500L));
            when(productRepository.findOnShelfSummaries(any(), any(), any(), any(), any(Integer.class)))
                    .thenReturn(rows);

            var item = service.listProducts(null, null, null, null, null, 20).items().getFirst();

            assertThat(item.skus()).isEmpty();
            assertThat(item.lowestPrice()).isEqualByComparingTo("100");
            assertThat(item.productId()).isEqualTo(500L);
        }
    }
}
