package com.flashsale.domain.shared;

/**
 * 業務規則違反時拋出的例外。
 *
 * <p>刻意不繼承任何框架例外，維持領域層純淨；由 API 層的 GlobalExceptionHandler 轉為 HTTP 回應。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 業務例外不需要堆疊，省下高併發下 fillInStackTrace 的可觀開銷。 */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
