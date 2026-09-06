package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.application.port.in.command.SeckillCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 搶購請求體。 */
public record SeckillRequest(

        @NotNull(message = "activityId 不可為空")
        Long activityId,

        @Min(value = 1, message = "購買數量至少為 1")
        @Max(value = 100, message = "單次購買數量過大")
        int quantity,

        /** 由前端在使用者按下按鈕前產生的冪等鍵（建議用 UUID）。 網路逾時後重送相同的值，可確保只會扣一次庫存、拿到同一張訂單。 */
        @NotBlank(message = "requestId 不可為空")
        @Size(max = 64, message = "requestId 長度不可超過 64")
        String requestId
) {

    public SeckillCommand toCommand(Long userId) {
        return new SeckillCommand(activityId, userId, quantity, requestId);
    }
}
