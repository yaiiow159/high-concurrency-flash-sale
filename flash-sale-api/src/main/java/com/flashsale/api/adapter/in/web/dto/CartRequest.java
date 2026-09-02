package com.flashsale.api.adapter.in.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 加入購物車 / 調整數量的請求體。
 *
 * <p>沒有價格欄位——價格由目錄決定。也沒有 userId——身分來自令牌。
 */
public record CartRequest(

        @NotNull(message = "skuId 不可為空")
        Long skuId,

        /** 調整數量時允許 0，代表移除；加入購物車時最小為 1（由 Use Case 驗證）。 */
        @Min(value = 0, message = "數量不可為負")
        @Max(value = 999, message = "單一品項數量過大")
        int quantity
) {
}
