package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.domain.shared.ErrorCode;

/**
 * 統一回應封裝。
 *
 * <p>{@code retryable} 是刻意放進契約的欄位：前端不必維護一份「哪些錯誤可以重試」的
 * 硬編碼清單，直接照這個旗標決定要不要自動重試。錯誤語意的權威應該在後端，
 * 前端各自解讀錯誤碼，遲早會出現不一致。
 */
public record ApiResponse<T>(
        String code,
        String message,
        T data,
        boolean retryable
) {

    private static final String SUCCESS_CODE = "00000";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, "success", data, false);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code(), message, null, errorCode.retryable());
    }
}
