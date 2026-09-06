package com.flashsale.api.adapter.in.web.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

/** 驗收退回品的請求體。 */
public record ReturnReceiveRequest(

        @NotEmpty(message = "必須提供每一個品項的驗收結果")
        Map<Long, Boolean> restockDecisions
) {
}
