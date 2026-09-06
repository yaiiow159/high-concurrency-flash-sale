package com.flashsale.application.service;

import com.flashsale.application.port.in.CatalogQueryUseCase;
import com.flashsale.application.port.in.dto.ProductView;
import com.flashsale.application.port.out.EngagementRepository;
import com.flashsale.domain.shared.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("收藏與瀏覽紀錄")
class EngagementServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);

    private EngagementRepository repository;
    private CatalogQueryUseCase catalog;
    private EngagementService service;

    @BeforeEach
    void setUp() {
        repository = mock(EngagementRepository.class);
        catalog = mock(CatalogQueryUseCase.class);
        service = new EngagementService(repository, catalog, CLOCK);
    }

    private static ProductView product(Long id) {
        return new ProductView(id, 1L, "商品 " + id, "牌子", null, "ON_SHELF",
                BigDecimal.TEN, List.of());
    }

    @Nested
    @DisplayName("收藏")
    class Wishlist {

        @Test
        @DisplayName("收藏不存在或已下架的商品時擋下")
        void rejectsUnknownProduct() {
            // 少了這一步，收藏頁會列出點進去 404 的東西，
            // 而使用者不會知道是自己收藏了一個已下架的商品
            when(catalog.findProductsByIds(any())).thenReturn(List.of());

            assertThatThrownBy(() -> service.addToWishlist(1L, 999L))
                    .isInstanceOf(BusinessException.class);
            verify(repository, never()).addToWishlist(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("上架商品可以收藏")
        void addsExistingProduct() {
            when(catalog.findProductsByIds(any())).thenReturn(List.of(product(5L)));

            service.addToWishlist(1L, 5L);

            verify(repository).addToWishlist(1L, 5L, CLOCK.instant());
        }

        @Test
        @DisplayName("清單依收藏順序輸出，已下架的跳過")
        void keepsOrderAndSkipsMissing() {
            when(repository.findWishlistProductIds(anyLong(), anyInt(), anyInt()))
                    .thenReturn(List.of(3L, 2L, 1L));
            // 商品 2 已下架，查不到
            when(catalog.findProductsByIds(any()))
                    .thenReturn(List.of(product(1L), product(3L)));

            assertThat(service.wishlist(1L, 0, 20))
                    .extracting(ProductView::productId)
                    .containsExactly(3L, 1L);
        }

        @Test
        @DisplayName("未登入時批次查詢回空集合，不打資料庫")
        void anonymousHasNoWishlist() {
            assertThat(service.wishlistedAmong(null, List.of(1L, 2L))).isEmpty();
            verify(repository, never()).findWishlistedAmong(any(), any());
        }
    }

    @Nested
    @DisplayName("瀏覽紀錄")
    class History {

        @Test
        @DisplayName("未登入不記")
        void anonymousIsNotRecorded() {
            service.recordView(null, 5L);
            verify(repository, never()).recordView(any(), any(), any());
        }

        @Test
        @DisplayName("寫入失敗不往外拋")
        void failureDoesNotPropagate() {
            // 瀏覽紀錄是附加價值，寫不進去不該讓商品頁打不開
            doThrow(new RuntimeException("db down"))
                    .when(repository).recordView(any(), any(), any());

            assertThatCode(() -> service.recordView(1L, 5L)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("最近看過依時間順序，已下架的跳過")
        void recentKeepsOrder() {
            when(repository.findRecentlyViewed(anyLong(), anyInt()))
                    .thenReturn(List.of(9L, 8L, 7L));
            when(catalog.findProductsByIds(any()))
                    .thenReturn(List.of(product(7L), product(9L)));

            assertThat(service.recentlyViewed(1L, 10))
                    .extracting(ProductView::productId)
                    .containsExactly(9L, 7L);
        }

        @Test
        @DisplayName("未登入時最近看過是空的")
        void anonymousHasNoHistory() {
            assertThat(service.recentlyViewed(null, 10)).isEmpty();
        }
    }

    @Nested
    @DisplayName("看了又看")
    class AlsoViewed {

        @Test
        @DisplayName("依關聯強度的順序輸出")
        void keepsRankingOrder() {
            // 順序是這個清單的全部意義——它就是「多少人一起看過」排出來的
            when(repository.findAlsoViewed(anyLong(), anyInt())).thenReturn(List.of(4L, 6L, 5L));
            when(catalog.findProductsByIds(any()))
                    .thenReturn(List.of(product(5L), product(4L), product(6L)));

            assertThat(service.alsoViewed(1L, 8))
                    .extracting(ProductView::productId)
                    .containsExactly(4L, 6L, 5L);
        }

        @Test
        @DisplayName("沒有足夠資料時回空清單，不是報錯")
        void emptyWhenNoData() {
            when(repository.findAlsoViewed(anyLong(), anyInt())).thenReturn(List.of());

            assertThat(service.alsoViewed(1L, 8)).isEmpty();
        }
    }
}
