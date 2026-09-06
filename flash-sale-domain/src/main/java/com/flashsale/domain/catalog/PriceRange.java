package com.flashsale.domain.catalog;

import java.math.BigDecimal;

/** 價格區間篩選。 */
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
