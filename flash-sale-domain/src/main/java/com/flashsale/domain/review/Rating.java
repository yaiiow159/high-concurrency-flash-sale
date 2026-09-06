package com.flashsale.domain.review;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

/** 評分：一到五顆星。 */
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
