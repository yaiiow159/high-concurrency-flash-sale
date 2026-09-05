package com.flashsale.domain.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 分頁值物件。
 *
 * <p>這裡每一條測的都是<b>某一處真實存在過的分歧</b>：
 * 12 處各自夾取、11 處各自換算 offset，而其中兩處漏了除零守衛。
 * 值物件存在的意義就是讓這些情況只有一份定義。
 */
@DisplayName("分頁")
class PageTest {

    @Nested
    @DisplayName("夾取")
    class Clamping {

        @Test
        @DisplayName("size 為 0 或負數時用預設值，而不是報錯")
        void nonPositiveSizeFallsBackToDefault() {
            // 幾乎都是呼叫端漏帶參數，為此回 400 只會讓
            // 「列表打不開」變成一個要查的問題
            assertThat(Page.of(0, 0, 100).size()).isEqualTo(Page.DEFAULT_SIZE);
            assertThat(Page.of(0, -5, 100).size()).isEqualTo(Page.DEFAULT_SIZE);
        }

        @Test
        @DisplayName("size 有上限——沒有的話任何人都能用一個參數掃全表")
        void sizeIsCapped() {
            assertThat(Page.of(0, 1_000_000, 100).size()).isEqualTo(100);
        }

        @Test
        @DisplayName("負的頁碼歸零")
        void negativeNumberIsZero() {
            assertThat(Page.of(-3, 20, 100).number()).isZero();
        }

        @Test
        @DisplayName("maxSize 本身是 0 或負數時也不能算出非法的頁大小")
        void degenerateMaxSizeIsSurvivable() {
            // 呼叫端傳了 0 當上限是一個 bug，但它不該表現成
            // 「Math.clamp 的下界大於上界」那種難懂的例外
            assertThatCode(() -> Page.of(0, 20, 0)).doesNotThrowAnyException();
            assertThat(Page.of(0, 20, 0).size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("offset 換算")
    class FromOffset {

        @Test
        @DisplayName("limit 為 0 不可除以零")
        void zeroLimitDoesNotDivideByZero() {
            // 這正是 JpaReviewRepository 漏掉 Math.max 的那個情況。
            // 它今天走不到（Controller 有夾），但守衛在別層就是遲早的事
            assertThatCode(() -> Page.fromOffset(0, 100)).doesNotThrowAnyException();
            assertThat(Page.fromOffset(0, 100).size()).isEqualTo(1);
        }

        @Test
        @DisplayName("負的 offset 當成第一頁")
        void negativeOffsetIsFirstPage() {
            assertThat(Page.fromOffset(20, -40).number()).isZero();
        }

        @Test
        @DisplayName("換算與 offset() 互為逆運算")
        void roundTrip() {
            Page page = Page.of(7, 20, 100);

            assertThat(page.offset()).isEqualTo(140);
            assertThat(Page.fromOffset(20, 140).number()).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("多取一筆")
    class Lookahead {

        @Test
        @DisplayName("sizePlusOne 只多一筆，用來判斷有沒有下一頁")
        void oneExtra() {
            // 比再打一次 COUNT(*) 便宜得多——在 5 萬列上那個 count
            // 比查詢本身還貴，而它只是為了決定一個布林值
            assertThat(Page.of(0, 20, 100).sizePlusOne()).isEqualTo(21);
        }
    }
}
