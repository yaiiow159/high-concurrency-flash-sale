package com.flashsale.domain.catalog;

import java.math.BigDecimal;

/**
 * keyset 分頁的游標（ADR-0021）。
 *
 * <h2>為什麼不是單純一個 id</h2>
 *
 * <p>依 id 排序時，{@code id < 上一頁最後一筆} 就足以定位。
 * 但依價格排序時價格會重複——只用 {@code price > 上一筆價格}
 * 會<b>跳過所有同價格的商品</b>，而只用 {@code >=} 會<b>重複顯示</b>它們。
 *
 * <p>正解是把 id 當作決勝鍵：
 * {@code (price, id) > (上一筆的 price, 上一筆的 id)}。
 * id 唯一，因此複合鍵也唯一，不會跳號也不會重複。
 *
 * @param sortValue 排序欄位的值；依 id 排序時為 {@code null}。
 *                  用 {@link BigDecimal} 而不是 {@code long}——價格有小數，
 *                  截成整數的話同一元內的商品全部變成同值，
 *                  翻頁就會在那裡重複或跳號
 * @param id        決勝鍵，永遠都要有
 */
public record ProductCursor(BigDecimal sortValue, Long id) {

    private static final String SEPARATOR = ":";

    public static ProductCursor ofId(Long id) {
        return new ProductCursor(null, id);
    }

    public static ProductCursor of(BigDecimal sortValue, Long id) {
        return new ProductCursor(sortValue, id);
    }

    /**
     * 編碼成一個對前端不透明的字串。
     *
     * <p>刻意<b>不用 JSON 也不做 base64</b>：base64 會讓人以為它是加密的，
     * 而它不是——游標的內容本來就沒有秘密，它只是不該被前端解讀。
     * 真正的保護是「解不開就當第一頁」，而不是把它藏起來。
     */
    public String encode() {
        return sortValue == null
                ? String.valueOf(id)
                : sortValue.toPlainString() + SEPARATOR + id;
    }

    /**
     * 解碼。<b>解不開時回 {@code null}（當作第一頁），不報錯。</b>
     *
     * <p>游標會出現在網址上，而使用者會改它、也會貼舊連結——
     * 逛商品列表這件事不該因為網址被改壞而失敗。
     */
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
