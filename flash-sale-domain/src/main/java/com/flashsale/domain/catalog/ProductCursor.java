package com.flashsale.domain.catalog;

import java.math.BigDecimal;

/** keyset 分頁的游標（ADR-0021）。 */
public record ProductCursor(BigDecimal sortValue, Long id) {

    private static final String SEPARATOR = ":";

    public static ProductCursor ofId(Long id) {
        return new ProductCursor(null, id);
    }

    public static ProductCursor of(BigDecimal sortValue, Long id) {
        return new ProductCursor(sortValue, id);
    }

    /** 編碼成一個對前端不透明的字串。 */
    public String encode() {
        return sortValue == null
                ? String.valueOf(id)
                : sortValue.toPlainString() + SEPARATOR + id;
    }

    /** 解碼。<b>解不開時回 {@code null}（當作第一頁），不報錯。</b> */
    public static ProductCursor decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(SEPARATOR, 2);
        try {
            return parts.length == 1
                    ? ofId(Long.parseLong(parts[0]))
                    : of(new BigDecimal(parts[0]), Long.parseLong(parts[1]));
        } catch (NumberFormatException | ArithmeticException ignored) {
            return null;
        }
    }
}
