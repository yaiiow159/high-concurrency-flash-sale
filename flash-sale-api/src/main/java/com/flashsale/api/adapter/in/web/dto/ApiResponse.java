package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.domain.shared.ErrorCode;

/** 統一回應封裝。 */
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
