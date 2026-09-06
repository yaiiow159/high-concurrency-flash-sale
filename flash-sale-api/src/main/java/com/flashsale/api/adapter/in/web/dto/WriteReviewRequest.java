package com.flashsale.api.adapter.in.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 發表或修改評價的請求體。 */
public record WriteReviewRequest(

        Long skuId,

        @Min(value = 1, message = "評分至少 1 星")
        @Max(value = 5, message = "評分最多 5 星")
        int stars,

        @NotBlank(message = "評價內容不可為空")
        @Size(max = 1000, message = "評價內容不可超過 1000 字")
        String content
) {
}
