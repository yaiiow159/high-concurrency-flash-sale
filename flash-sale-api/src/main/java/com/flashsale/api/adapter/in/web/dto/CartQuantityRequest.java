package com.flashsale.api.adapter.in.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 調整購物車數量的請求體。
 *
 * <p><b>刻意不含 skuId。</b> 要改哪一個品項由路徑決定，
 * 而讓請求體也帶一個 skuId 會製造一個沒有答案的問題：
 * 兩邊不一致時該聽誰的。先前共用 {@link CartRequest} 的結果是
 * 「必填、但完全忽略」——送 {@code PUT /items/2003} 配
 * {@code {"skuId": 999}} 不會報錯，改到的是 2003。
 */
public record CartQuantityRequest(

        /** 允許 0，代表移除。 */
        @Min(value = 0, message = "數量不可為負")
        @Max(value = 999, message = "單一品項數量過大")
        int quantity
) {
}
