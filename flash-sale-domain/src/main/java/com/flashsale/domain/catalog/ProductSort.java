package com.flashsale.domain.catalog;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.Arrays;

/** 商品列表的排序方式。 */
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

    /** 解析前端傳來的字串。 */
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
