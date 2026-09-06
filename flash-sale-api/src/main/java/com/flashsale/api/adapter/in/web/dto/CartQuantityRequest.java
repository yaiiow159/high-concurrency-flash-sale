package com.flashsale.api.adapter.in.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 調整購物車數量的請求體。 */
public record CartQuantityRequest(

        /** 允許 0，代表移除。 */
        @Min(value = 0, message = "數量不可為負")
        @Max(value = 999, message = "單一品項數量過大")
        int quantity
) {
}
