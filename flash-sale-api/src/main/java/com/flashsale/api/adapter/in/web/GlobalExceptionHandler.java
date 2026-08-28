package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全域例外處理。
 *
 * <p>集中在一處的價值：<b>錯誤回應的格式與狀態碼只有一個定義點</b>。
 * 若讓每個 Controller 自己 try-catch，同一種錯誤在不同端點會回不同的格式，
 * 前端只能逐一特判。
 *
 * <p>錯誤碼到 HTTP 狀態碼的映射寫成一張表，而非散落的 if-else：
 * 新增錯誤碼時漏了映射，會落到明確的預設值，而不是意外回一個 200。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 錯誤碼 → HTTP 狀態碼。
     *
     * <p>未列出的錯誤碼依前綴決定：{@code C}（系統故障）→ 503，其餘 → 409。
     */
    private static final Map<ErrorCode, HttpStatus> STATUS_MAPPING = Map.of(
            ErrorCode.INVALID_PARAMETER, HttpStatus.BAD_REQUEST,
            ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
            ErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED,
            ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN,
            ErrorCode.ACTIVITY_NOT_FOUND, HttpStatus.NOT_FOUND,
            ErrorCode.ORDER_NOT_FOUND, HttpStatus.NOT_FOUND,
            ErrorCode.SOLD_OUT, HttpStatus.CONFLICT,
            ErrorCode.USER_PURCHASE_LIMIT_EXCEEDED, HttpStatus.CONFLICT
    );

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode errorCode = e.errorCode();
        HttpStatus status = resolveStatus(errorCode);

        // 系統故障需要排查，記完整堆疊；業務拒絕是預期內的正常結果，記一行就好。
        // 秒殺尖峰時「已售罄」每秒可能發生數萬次，全記堆疊會先把磁碟寫爆。
        if (status.is5xxServerError()) {
            log.error("系統錯誤 code={}", errorCode.code(), e);
        } else {
            log.debug("業務拒絕 code={}, message={}", errorCode.code(), e.getMessage());
        }
        return ResponseEntity.status(status).body(ApiResponse.error(errorCode, e.getMessage()));
    }

    /** Resilience4j 限流觸發時拋出的例外，轉為標準的 429。 */
    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimited(RequestNotPermitted e) {
        log.debug("觸發單機限流: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.error(ErrorCode.RATE_LIMITED, ErrorCode.RATE_LIMITED.defaultMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_PARAMETER, message));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_PARAMETER, "缺少必要標頭: " + e.getHeaderName()));
    }

    /**
     * 兜底處理。
     *
     * <p>回傳的訊息刻意不含例外細節——堆疊資訊可能洩漏內部類別名稱、SQL 片段甚至連線字串。
     * 詳情記在伺服器日誌，對外只給一個通用訊息。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("未預期的例外", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.SYSTEM_BUSY, "系統異常，請稍後再試"));
    }

    private HttpStatus resolveStatus(ErrorCode errorCode) {
        HttpStatus mapped = STATUS_MAPPING.get(errorCode);
        if (mapped != null) {
            return mapped;
        }
        return errorCode.retryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.CONFLICT;
    }
}
