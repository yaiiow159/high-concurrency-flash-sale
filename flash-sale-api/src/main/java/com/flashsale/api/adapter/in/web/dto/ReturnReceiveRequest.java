package com.flashsale.api.adapter.in.web.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

/**
 * 驗收退回品的請求體。
 *
 * <p>{@code restockDecisions} 必須涵蓋退貨單上的<b>每一行</b>，
 * 漏掉時由聚合根拋例外而非預設為可再售——
 * 那個預設值會把毀損品的成本靜靜地算成庫存。
 */
public record ReturnReceiveRequest(

        @NotEmpty(message = "必須提供每一個品項的驗收結果")
        Map<Long, Boolean> restockDecisions
) {
}
