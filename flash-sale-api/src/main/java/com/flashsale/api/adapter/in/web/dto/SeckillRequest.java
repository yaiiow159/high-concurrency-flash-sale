package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.application.port.in.command.SeckillCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 搶購請求。qualificationToken 是開賣前領到的資格憑證；要不要驗由後端設定決定。 */
public record SeckillRequest(
        @NotNull(message = "activityId 不可為空")
        Long activityId,

        @Min(value = 1, message = "購買數量至少為 1")
        @Max(value = 100, message = "單次購買數量過大")
        int quantity,

        @NotBlank(message = "requestId 不可為空")
        @Size(max = 64, message = "requestId 長度不可超過 64")
        String requestId,

        @Size(max = 512, message = "qualificationToken 長度不可超過 512")
        String qualificationToken
) {

    public SeckillCommand toCommand(Long userId) {
        return new SeckillCommand(activityId, userId, quantity, requestId, qualificationToken);
    }
}
