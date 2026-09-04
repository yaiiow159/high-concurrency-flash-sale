package com.flashsale.domain.review;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

/**
 * 評分：一到五顆星。
 *
 * <h2>為什麼是值物件而不是 int</h2>
 *
 * <p>評分會被當成陣列索引（分佈桶）、會相加（聚合）、會相減（改評價）。
 * 裸 {@code int} 讓「0 分」與「6 分」在編譯期都是合法的，
 * 而那兩個值一旦寫進 {@code product_rating} 的分佈欄位，
 * 就是一個必須手動修的髒資料。
 *
 * <p>驗證放在這裡而不是每個呼叫端各寫一次——
 * 少寫一處，那一處就是漏洞。
 */
public record Rating(int stars) {

    public static final int MIN = 1;
    public static final int MAX = 5;

    public Rating {
        if (stars < MIN || stars > MAX) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "評分必須介於 %d 與 %d 之間".formatted(MIN, MAX));
        }
    }

    public static Rating of(int stars) {
        return new Rating(stars);
    }
}
