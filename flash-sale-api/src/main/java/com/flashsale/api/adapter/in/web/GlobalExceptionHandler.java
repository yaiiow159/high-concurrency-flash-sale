package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/** 全域例外處理。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 錯誤碼 → HTTP 狀態碼。 */
    // 用 Map.ofEntries 而非 Map.of：後者上限 10 組，加到第 11 個錯誤碼時
    // 會是一個難以一眼看出原因的編譯錯誤。
    private static final Map<ErrorCode, HttpStatus> STATUS_MAPPING = Map.ofEntries(
            Map.entry(ErrorCode.INVALID_PARAMETER, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.ENDPOINT_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.CONCURRENT_MODIFICATION, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.ILLEGAL_ACTIVITY_STATE_TRANSITION, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS),
            Map.entry(ErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED),
            // 認證失敗一律 401：前端攔截器靠這個狀態碼決定要不要觸發續期或導向登入。
            // 回 409 會讓自動續期邏輯完全失效。
            Map.entry(ErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED),
            Map.entry(ErrorCode.INVALID_REFRESH_TOKEN, HttpStatus.UNAUTHORIZED),
            Map.entry(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN),
            // 帳號停權是「知道你是誰但不讓你進」，屬於 403 而非 401——
            // 回 401 會讓前端誤以為重新登入就能解決。
            Map.entry(ErrorCode.ACCOUNT_SUSPENDED, HttpStatus.FORBIDDEN),
            Map.entry(ErrorCode.ACTIVITY_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.ORDER_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.USER_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.SOLD_OUT, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.USER_PURCHASE_LIMIT_EXCEEDED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.EMAIL_ALREADY_REGISTERED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.PAYMENT_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.ORDER_NOT_PAYABLE, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.ILLEGAL_PAYMENT_STATE_TRANSITION, HttpStatus.CONFLICT),
            // 簽章錯誤回 401 而非 400：這是「你不是你宣稱的那個閘道」，屬於認證問題
            Map.entry(ErrorCode.INVALID_CALLBACK_SIGNATURE, HttpStatus.UNAUTHORIZED),
            Map.entry(ErrorCode.PRODUCT_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.SKU_NOT_FOUND, HttpStatus.NOT_FOUND),
            // 庫存維運的兩種拒絕都是「目前的狀態不允許這個操作」，不是請求寫錯
            Map.entry(ErrorCode.INVENTORY_NOT_FOUND, HttpStatus.NOT_FOUND),
            // 查不到與無權限都回 404：回 403 等於確認這個 ID 是有效的
            Map.entry(ErrorCode.ADDRESS_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.ADDRESS_LIMIT_EXCEEDED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.CART_ITEM_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.CART_ITEM_LIMIT_EXCEEDED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.CART_EMPTY, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.SHIPMENT_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.ILLEGAL_SHIPMENT_STATE_TRANSITION, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.ORDER_NOT_SHIPPABLE, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.SHIPPING_INFO_REQUIRED, HttpStatus.CONFLICT),
            // 查不到退貨單同樣回 404，理由與地址一致：回 403 等於確認這個單號存在
            Map.entry(ErrorCode.RETURN_REQUEST_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.ILLEGAL_RETURN_STATE_TRANSITION, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.ORDER_NOT_RETURNABLE, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.RETURN_QUANTITY_EXCEEDED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.REFUND_AMOUNT_EXCEEDED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.NOTIFICATION_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.INSUFFICIENT_INVENTORY_TO_ALLOCATE, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.INVENTORY_RELEASE_EXCEEDS_ALLOCATION, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.ACTIVITY_STOCK_ALREADY_RELEASED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.ACTIVITY_NOT_COOLED_DOWN, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.CATEGORY_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.PRODUCT_NOT_PURCHASABLE, HttpStatus.CONFLICT),
            // 券的三種拒絕理由要分得開：404 代表這張券不是你的（或不存在），
            // 409 代表券是你的但現在不能用。前端據此決定要不要把券從清單上移除
            Map.entry(ErrorCode.COUPON_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.COUPON_ALREADY_USED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.COUPON_EXPIRED, HttpStatus.CONFLICT),
            // 查不到評價回 404，與地址、退貨單一致：回 403 等於確認這個 ID 存在
            Map.entry(ErrorCode.REVIEW_NOT_FOUND, HttpStatus.NOT_FOUND),
            // 其餘三種都是「狀態不允許」而不是「請求寫錯」
            Map.entry(ErrorCode.ORDER_NOT_REVIEWABLE, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.ALREADY_REVIEWED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.REVIEW_EDIT_WINDOW_CLOSED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.PROMOTION_NOT_FOUND, HttpStatus.NOT_FOUND),
            // 「不開放兌換」與「積分不足」都是狀態問題而不是請求寫錯：
            // 同一個請求在別的時間點或別的餘額下會成功
            Map.entry(ErrorCode.PROMOTION_NOT_EXCHANGEABLE, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.INSUFFICIENT_POINTS, HttpStatus.CONFLICT),
            // 費率表有缺口是**設定問題**而不是使用者輸入錯誤。
            // 回 409 而非 400：同一個請求在補上費率之後會成功
            Map.entry(ErrorCode.SHIPPING_RATE_NOT_FOUND, HttpStatus.CONFLICT)
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

    /** 請求體讀不動：JSON 語法錯、型別對不上、列舉值不在允許範圍內。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(
            HttpMessageNotReadableException e) {
        log.debug("請求體無法解析: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_PARAMETER,
                        "請求內容格式不正確，請確認欄位型別與必填欄位"));
    }

    /** 查詢參數或路徑變數轉不出來：列舉值不在允許範圍、數字欄位收到文字。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException e) {
        log.debug("參數型別不符: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_PARAMETER,
                        "參數「%s」的值不正確".formatted(e.getName())));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_PARAMETER, "缺少必要標頭: " + e.getHeaderName()));
    }

    /** 樂觀鎖衝突。 */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleConcurrentModification(
            ObjectOptimisticLockingFailureException e) {
        log.warn("併發修改衝突，請求已回滾", e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ErrorCode.CONCURRENT_MODIFICATION,
                        "這筆資料剛剛被其他人修改過，請重新整理後再試"));
    }

    /** 路徑不存在。 */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ErrorCode.ENDPOINT_NOT_FOUND, "找不到這個路徑"));
    }

    /** 拿不到資料庫連線。 */
    @ExceptionHandler({CannotCreateTransactionException.class,
            CannotAcquireLockException.class,
            QueryTimeoutException.class})
    public ResponseEntity<ApiResponse<Void>> handleDatabaseCapacity(Exception e) {
        log.warn("資料庫連線或鎖等待逾時，請求已拒絕：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(ErrorCode.SYSTEM_BUSY, "系統忙碌中，請稍後再試"));
    }

    /** 兜底處理。 */
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
