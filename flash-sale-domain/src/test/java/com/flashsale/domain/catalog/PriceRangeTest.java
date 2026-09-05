package com.flashsale.domain.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 價格區間。
 *
 * <p>這裡每一條處理的都是<b>使用者手滑</b>，而不是攻擊。
 * 手滑回 400 只會讓他盯著兩個看起來都沒問題的數字，
 * 而正確的行為是猜出他想要什麼。
 */
@DisplayName("價格區間")
class PriceRangeTest {

    @Test
    @DisplayName("上下限顛倒時自動對調，不報錯")
    void swapsInvertedBounds() {
        // 把 1000 打在「最低」、100 打在「最高」是很常見的手滑
        PriceRange range = PriceRange.of(new BigDecimal("1000"), new BigDecimal("100"));

        assertThat(range.min()).isEqualByComparingTo("100");
        assertThat(range.max()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("負數當成沒填——價格不可能是負的")
    void negativeIsUnbounded() {
        PriceRange range = PriceRange.of(new BigDecimal("-5"), new BigDecimal("300"));

        assertThat(range.hasMin()).isFalse();
        assertThat(range.max()).isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("兩端都可以不填")
    void bothOptional() {
        assertThat(PriceRange.of(null, null)).isEqualTo(PriceRange.UNBOUNDED);
        assertThat(PriceRange.of(new BigDecimal("100"), null).hasMax()).isFalse();
        assertThat(PriceRange.of(null, new BigDecimal("100")).hasMin()).isFalse();
    }

    @Test
    @DisplayName("上下限相同是合法的——就是「剛好這個價格」")
    void equalBoundsAreValid() {
        PriceRange range = PriceRange.of(new BigDecimal("299"), new BigDecimal("299"));

        assertThat(range.min()).isEqualByComparingTo("299");
        assertThat(range.max()).isEqualByComparingTo("299");
    }

    @Test
    @DisplayName("零是合法的下限，不可被當成沒填")
    void zeroIsAValidBound() {
        // 「0 元起」是真的有意義（贈品、試用），
        // 而把 0 當成 falsy 過濾掉是很常見的錯誤
        assertThat(PriceRange.of(BigDecimal.ZERO, new BigDecimal("100")).hasMin()).isTrue();
    }
}
