package com.flashsale.api.adapter.in.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 發表或修改評價的請求體。
 *
 * <p><b>沒有 userId、沒有 authorName。</b> 身分來自令牌；
 * 顯示名稱由伺服器從帳號取出並遮蔽後寫入（ADR-0014 決策 6）。
 * 讓呼叫端送作者名稱，等於讓任何人以任何人的名義發表評價。
 *
 * <p>修改時也用這個型別，因為能改的就是星等與內容兩樣。
 * {@code skuId} 在修改路徑上會被忽略——要改的是哪一則由路徑參數決定。
 */
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
