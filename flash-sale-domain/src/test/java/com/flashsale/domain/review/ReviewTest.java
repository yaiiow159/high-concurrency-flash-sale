package com.flashsale.domain.review;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 評價聚合根。
 *
 * <p>重點在修改窗口的兩個容易寫錯的地方：窗口從<b>發表</b>算起（不是從上次修改），
 * 以及 {@code edit} 回傳新實例而不是就地修改——
 * 聚合的更新需要舊評分，就地修改會讓舊值消失。
 */
@DisplayName("商品評價")
class ReviewTest {

    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

    private static Review review(int stars) {
        return Review.create(1L, 2001L, "220600000000000001", 42L, "王＊＊",
                Rating.of(stars), "用起來很好", NOW);
    }

    @Nested
    @DisplayName("修改窗口")
    class EditWindow {

        @Test
        @DisplayName("窗口內可以改，而且回傳新實例——舊評分是聚合更新的必要輸入")
        void editReturnsANewInstance() {
            Review original = review(5);

            Review edited = original.edit(Rating.of(3), "用久了才發現問題",
                    NOW.plusSeconds(86400));

            // 原本那則必須還在，rating_sum += (new - old) 需要它
            assertThat(original.rating().stars()).isEqualTo(5);
            assertThat(edited.rating().stars()).isEqualTo(3);
            assertThat(edited.content()).isEqualTo("用久了才發現問題");
        }

        @Test
        @DisplayName("超過七天就不能改")
        void windowClosesAfterSevenDays() {
            Review original = review(5);
            Instant tooLate = NOW.plus(Review.EDIT_WINDOW).plusSeconds(1);

            assertThatThrownBy(() -> original.edit(Rating.of(1), "改一下", tooLate))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> assertThat(((BusinessException) thrown).errorCode())
                            .isEqualTo(ErrorCode.REVIEW_EDIT_WINDOW_CLOSED));
        }

        @Test
        @DisplayName("窗口從發表算起，不是從上次修改算起——否則改一次就能無限續期")
        void windowIsAnchoredToCreation() {
            Review original = review(5);

            // 第六天改一次
            Review edited = original.edit(Rating.of(4), "補充一下", NOW.plusSeconds(6 * 86400));

            // 第八天再改就該被擋下。若窗口從上次修改算起，這裡會通過
            assertThatThrownBy(() -> edited.edit(Rating.of(1), "再改", NOW.plusSeconds(8 * 86400)))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("改過的評價要標記出來——讀者有權知道這不是原始版本")
        void editedIsMarked() {
            Review original = review(5);

            assertThat(original.isEdited()).isFalse();
            assertThat(original.edit(Rating.of(4), "補充", NOW.plusSeconds(60)).isEdited()).isTrue();
        }
    }

    @Nested
    @DisplayName("擁有權")
    class Ownership {

        @Test
        @DisplayName("別人的評價當作不存在——回「不是你的」等於確認這個 ID 有效")
        void othersReviewLooksMissing() {
            Review review = review(5);

            assertThatThrownBy(() -> review.requireOwnedBy(99L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> assertThat(((BusinessException) thrown).errorCode())
                            .isEqualTo(ErrorCode.REVIEW_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("內容")
    class Content {

        @Test
        @DisplayName("空白內容會被擋下——只有星等的評價對讀者沒有價值")
        void blankContentIsRejected() {
            assertThatThrownBy(() -> Review.create(1L, 2001L, "ORD", 42L, "王＊＊",
                    Rating.of(5), "   ", NOW))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("超長內容會被擋下——一則評價不該塞爆商品頁")
        void overlongContentIsRejected() {
            String tooLong = "字".repeat(Review.MAX_CONTENT_LENGTH + 1);

            assertThatThrownBy(() -> Review.create(1L, 2001L, "ORD", 42L, "王＊＊",
                    Rating.of(5), tooLong, NOW))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
