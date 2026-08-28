package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.application.port.in.command.SeckillCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 搶購請求體。
 *
 * <p>與 {@link SeckillCommand} 分開是刻意的：這是<b>對外契約</b>，會被前端與第三方依賴，
 * 改動成本高；Command 是<b>內部模型</b>，應該能自由重構。
 * 若讓 Controller 直接收 Command，任何內部重構都會變成一次 breaking change。
 *
 * <p>{@code userId} 不在此處——它來自認證脈絡而非請求體。
 * 若讓呼叫端自己填 userId，任何人都能冒用他人身分下單。
 */
public record SeckillRequest(

        @NotNull(message = "activityId 不可為空")
        Long activityId,

        @Min(value = 1, message = "購買數量至少為 1")
        @Max(value = 100, message = "單次購買數量過大")
        int quantity,

        /**
         * 由前端在使用者按下按鈕前產生的冪等鍵（建議用 UUID）。
         * 網路逾時後重送相同的值，可確保只會扣一次庫存、拿到同一張訂單。
         */
        @NotBlank(message = "requestId 不可為空")
        @Size(max = 64, message = "requestId 長度不可超過 64")
        String requestId
) {

    public SeckillCommand toCommand(Long userId) {
        return new SeckillCommand(activityId, userId, quantity, requestId);
    }
}
