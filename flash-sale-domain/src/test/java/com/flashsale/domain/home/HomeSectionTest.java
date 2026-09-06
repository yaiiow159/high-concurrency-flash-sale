package com.flashsale.domain.home;

import com.flashsale.domain.shared.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("首頁版位")
class HomeSectionTest {

    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");

    private static HomeSection rail(ProductSource source, Long categoryId, List<Long> products) {
        return new HomeSection(1L, SectionType.PRODUCT_RAIL, "熱門商品", null,
                source, categoryId, products, 8, 0, Visibility.always());
    }

    @Nested
    @DisplayName("內容來源")
    class Source {

        @Test
        @DisplayName("依類目取商品卻沒指定類目時擋下")
        void categorySourceNeedsCategory() {
            // 少了這個檢查，設錯的版位會在首頁變成一個空白區塊——
            // 而空白區塊看起來像壞掉，不像設定沒填完
            assertThatThrownBy(() -> rail(ProductSource.CATEGORY, null, List.of()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("必須指定類目");
        }

        @Test
        @DisplayName("人工選品卻沒選任何商品時擋下")
        void curatedSourceNeedsProducts() {
            assertThatThrownBy(() -> rail(ProductSource.CURATED, null, List.of()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("至少要選一件");
        }

        @Test
        @DisplayName("規則型來源不需要額外參數")
        void ruleSourcesNeedNothingElse() {
            assertThatCode(() -> rail(ProductSource.BEST_SELLING, null, List.of()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("輪播版位不受商品來源檢查影響")
        void carouselSkipsSourceCheck() {
            assertThatCode(() -> new HomeSection(1L, SectionType.CAROUSEL, "主視覺", null,
                    null, null, List.of(), 5, 0, Visibility.always()))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("上架期間")
    class Schedule {

        private static HomeSection scheduled(Instant from, Instant to) {
            return new HomeSection(1L, SectionType.PRODUCT_RAIL, "當季限定", null,
                    ProductSource.BEST_SELLING, null, List.of(), 8, 0,
                    new Visibility(true, from, to));
        }

        @Test
        @DisplayName("還沒到開始時間就不顯示")
        void hiddenBeforeStart() {
            assertThat(scheduled(NOW.plusSeconds(60), null).isVisibleAt(NOW)).isFalse();
        }

        @Test
        @DisplayName("過了結束時間就不顯示")
        void hiddenAfterEnd() {
            assertThat(scheduled(null, NOW.minusSeconds(1)).isVisibleAt(NOW)).isFalse();
        }

        @Test
        @DisplayName("期間內顯示")
        void visibleWithinWindow() {
            assertThat(scheduled(NOW.minusSeconds(60), NOW.plusSeconds(60)).isVisibleAt(NOW))
                    .isTrue();
        }

        @Test
        @DisplayName("兩端都不設就一直顯示")
        void visibleWithoutWindow() {
            assertThat(scheduled(null, null).isVisibleAt(NOW)).isTrue();
        }

        @Test
        @DisplayName("停用時即使在期間內也不顯示")
        void disabledBeatsSchedule() {
            HomeSection off = new HomeSection(1L, SectionType.PRODUCT_RAIL, "x", null,
                    ProductSource.NEWEST, null, List.of(), 8, 0,
                    new Visibility(false, null, null));
            assertThat(off.isVisibleAt(NOW)).isFalse();
        }

        @Test
        @DisplayName("結束時間不晚於開始時間時擋下")
        void rejectsInvertedWindow() {
            assertThatThrownBy(() -> new Visibility(true, NOW, NOW.minusSeconds(1)))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("其他約束")
    class Constraints {

        @Test
        @DisplayName("標題不可為空")
        void titleRequired() {
            assertThatThrownBy(() -> new HomeSection(1L, SectionType.PRODUCT_RAIL, "  ", null,
                    ProductSource.NEWEST, null, List.of(), 8, 0, Visibility.always()))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("商品數超過上限時擋下")
        void itemLimitCapped() {
            // 再多首頁會變成商品列表，而那已經有專門的頁面
            assertThatThrownBy(() -> new HomeSection(1L, SectionType.PRODUCT_RAIL, "x", null,
                    ProductSource.NEWEST, null, List.of(), HomeSection.MAX_ITEMS + 1, 0,
                    Visibility.always()))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("選品清單不可被外部改動")
        void productIdsAreImmutable() {
            List<Long> mutable = new java.util.ArrayList<>(List.of(1L, 2L));
            HomeSection section = rail(ProductSource.CURATED, null, mutable);
            mutable.add(3L);

            assertThat(section.productIds()).containsExactly(1L, 2L);
        }
    }

    @Nested
    @DisplayName("輪播圖")
    class Slides {

        private static CarouselSlide slide(String link) {
            return new CarouselSlide(1L, "abc.png", "主視覺", link, 0, Visibility.always());
        }

        @Test
        @DisplayName("站內相對路徑放行")
        void internalLinkAllowed() {
            assertThatCode(() -> slide("/products/1")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("不填連結也可以")
        void linkIsOptional() {
            assertThatCode(() -> slide(null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("外部網址擋下")
        void externalLinkRejected() {
            // 放行外部網址等於讓能編輯輪播圖的人在首頁掛任意連結，
            // 而那是釣魚頁最想要的位置
            assertThatThrownBy(() -> slide("https://evil.example.com"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("協定相對網址也擋下")
        void protocolRelativeRejected() {
            // //evil.com 會被瀏覽器當成外部網址，但它「以 / 開頭」——
            // 只檢查開頭的話會漏掉這一種
            assertThatThrownBy(() -> slide("//evil.example.com"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("沒有圖片的輪播擋下")
        void imageRequired() {
            assertThatThrownBy(() -> new CarouselSlide(1L, " ", "t", null, 0, Visibility.always()))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
