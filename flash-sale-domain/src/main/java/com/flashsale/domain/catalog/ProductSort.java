package com.flashsale.domain.catalog;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.Arrays;

/**
 * 商品列表的排序方式。
 *
 * <h2>排序鍵決定游標的形狀</h2>
 *
 * <p>ADR-0021 的「不做的事」早就寫下這個相依：keyset 分頁的游標
 * 必須能<b>唯一定位</b>一列。依 id 排序時 id 自己就夠；
 * 依價格排序時不夠——價格會重複，光靠 {@code price < ?} 會
 * <b>跳過同價格的其他商品</b>，而那不會拋任何錯誤，只是有些商品永遠看不到。
 *
 * <p>因此非唯一的排序鍵一律配一個 {@code (排序值, id)} 的複合游標，
 * 由 {@link ProductCursor} 編解碼。
 */
public enum ProductSort {

    /** 最新上架。id 遞減即可近似，且 id 唯一，游標只需要 id。 */
    NEWEST(false),
    /** 價格由低到高。 */
    PRICE_ASC(true),
    /** 價格由高到低。 */
    PRICE_DESC(true),
    /** 銷量。沒有銷量資料的商品排在後面，而不是被濾掉。 */
    BEST_SELLING(true),
    /** 評分。同樣需要複合游標。 */
    RATING(true);

    private final boolean compositeCursor;

    ProductSort(boolean compositeCursor) {
        this.compositeCursor = compositeCursor;
    }

    /** 這個排序需不需要 {@code (排序值, id)} 的複合游標。 */
    public boolean needsCompositeCursor() {
        return compositeCursor;
    }

    /**
     * 解析前端傳來的字串。
     *
     * <p><b>認不得的值報錯，不默默退回預設。</b> 悄悄改成 NEWEST 的話，
     * 使用者選了「價格由低到高」卻看到最新上架，而畫面上的選單
     * 仍然停在他選的那一項——他會以為排序功能壞了，而我們什麼都不知道。
     */
    public static ProductSort parse(String value) {
        if (value == null || value.isBlank()) {
            return NEWEST;
        }
        return Arrays.stream(values())
                .filter(sort -> sort.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PARAMETER,
                        "不支援的排序方式: " + value));
    }
}
