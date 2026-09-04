package com.flashsale.domain.review;

import com.flashsale.domain.shared.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 評分聚合。
 *
 * <p>這裡釘住的是「存總和與筆數，不存平均值」這個決定所帶來的性質：
 * 平均可以由兩者算出來，而反過來不行。
 *
 * <p>另外釘住空狀態——沒有評價的商品是<b>絕大多數</b>商品的常態，
 * 而那條路徑最容易在除以零、null 星等上炸掉。
 */
@DisplayName("評分聚合")
class ProductRatingTest {

    @Nested
    @DisplayName("平均分")
    class Average {

        @Test
        @DisplayName("平均由總和除以筆數，取到小數一位")
        void averageIsDerived() {
            // 5 + 4 + 4 + 3 = 16，四則 → 4.0
            ProductRating rating = ProductRating.of(1L, 16, 4, 0, 0, 1, 2, 1);

            assertThat(rating.average()).isEqualByComparingTo("4.0");
        }

        @Test
        @DisplayName("四捨五入而非捨去——4.25 顯示成 4.2 會讓人覺得系統在扣分")
        void averageRoundsHalfUp() {
            // 17 / 4 = 4.25
            ProductRating rating = ProductRating.of(1L, 17, 4, 0, 0, 0, 3, 1);

            assertThat(rating.average()).isEqualByComparingTo("4.3");
        }

        @Test
        @DisplayName("沒有評價時回 0 而不是除以零")
        void emptyDoesNotDivideByZero() {
            ProductRating empty = ProductRating.empty(1L);

            assertThat(empty.average()).isEqualByComparingTo("0.0");
            assertThat(empty.hasReviews()).isFalse();
        }
    }

    @Nested
    @DisplayName("分佈")
    class Distribution {

        @Test
        @DisplayName("百分比由後端算——兩邊各算一次會讓長條加起來不是 100%")
        void percentagesComeFromTheServer() {
            ProductRating rating = ProductRating.of(1L, 34, 10, 1, 0, 1, 2, 6);

            assertThat(rating.percentageOf(5)).isEqualTo(60);
            assertThat(rating.percentageOf(1)).isEqualTo(10);
            assertThat(rating.percentageOf(2)).isZero();
        }

        @Test
        @DisplayName("分佈由高星到低星，那是評價區長條圖的呈現順序")
        void distributionIsDescending() {
            ProductRating rating = ProductRating.of(1L, 34, 10, 1, 0, 1, 2, 6);

            assertThat(rating.distributionDesc())
                    .extracting(java.util.Map.Entry::getKey)
                    .containsExactly(5, 4, 3, 2, 1);
        }

        @Test
        @DisplayName("沒有評價時每一格都是 0%，不是 NaN")
        void emptyDistributionIsZero() {
            ProductRating empty = ProductRating.empty(1L);

            assertThat(empty.percentageOf(5)).isZero();
            assertThat(empty.countOf(3)).isZero();
        }

        @Test
        @DisplayName("超出範圍的星等回 0，不拋例外——那是查詢不是寫入")
        void outOfRangeStarsReturnZero() {
            ProductRating rating = ProductRating.of(1L, 5, 1, 0, 0, 0, 0, 1);

            assertThat(rating.countOf(0)).isZero();
            assertThat(rating.countOf(6)).isZero();
        }
    }

    @Nested
    @DisplayName("不可變")
    class Immutability {

        @Test
        @DisplayName("拿到的分佈陣列改不動內部狀態——否則就能繞過建構子的驗證")
        void countsArrayIsDefensivelyCopied() {
            ProductRating rating = ProductRating.of(1L, 5, 1, 0, 0, 0, 0, 1);

            int[] leaked = rating.counts();
            leaked[5] = 999;

            assertThat(rating.countOf(5)).isEqualTo(1);
        }

        @Test
        @DisplayName("分佈桶數不對會被擋下")
        void wrongBucketCountIsRejected() {
            assertThatThrownBy(() -> new ProductRating(1L, 0, 0, new int[3]))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("評分本身")
    class Ratings {

        @Test
        @DisplayName("只接受一到五顆星——0 與 6 寫進分佈桶就是要手動修的髒資料")
        void starsAreBounded() {
            assertThatThrownBy(() -> Rating.of(0)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> Rating.of(6)).isInstanceOf(BusinessException.class);
            assertThat(Rating.of(1).stars()).isEqualTo(1);
            assertThat(Rating.of(5).stars()).isEqualTo(5);
        }
    }
}
