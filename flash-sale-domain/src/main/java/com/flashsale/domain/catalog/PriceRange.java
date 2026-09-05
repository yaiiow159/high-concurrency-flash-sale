package com.flashsale.domain.catalog;

import java.math.BigDecimal;

/**
 * 價格區間篩選。
 *
 * <p>兩端都可以是 {@code null}（不限）。
 *
 * <p><b>上下限顛倒時自動對調</b>而不是報錯：使用者把 1000 打在「最低」、
 * 100 打在「最高」是很常見的手滑，而回一個「參數錯誤」只會讓他
 * 盯著兩個看起來都沒問題的數字。對調之後結果就是他想要的。
 */
public record PriceRange(BigDecimal min, BigDecimal max) {

    public static final PriceRange UNBOUNDED = new PriceRange(null, null);

    public static PriceRange of(BigDecimal min, BigDecimal max) {
        BigDecimal safeMin = normalize(min);
        BigDecimal safeMax = normalize(max);
        if (safeMin != null && safeMax != null && safeMin.compareTo(safeMax) > 0) {
            return new PriceRange(safeMax, safeMin);
        }
        return new PriceRange(safeMin, safeMax);
    }

    /** 負數當成沒填——價格不可能是負的，而那多半是打錯而不是刻意的。 */
    private static BigDecimal normalize(BigDecimal value) {
        return value == null || value.signum() < 0 ? null : value;
    }

    public boolean hasMin() {
        return min != null;
    }

    public boolean hasMax() {
        return max != null;
    }
}
