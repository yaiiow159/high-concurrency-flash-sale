package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.dto.SeckillRequest;
import com.flashsale.application.port.in.OrderQueryUseCase;
import com.flashsale.application.port.in.SeckillUseCase;
import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.application.port.in.dto.SeckillTicket;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搶購 API。
 *
 * <p>Controller 只做四件事：取出認證身分、驗證輸入格式、委派給 Use Case、決定 HTTP 語意。
 * 一行業務邏輯都不該出現在這裡——真正的規則屬於領域層，
 * 混進 Controller 就再也無法脫離 HTTP 測試它。
 *
 * <p><b>兩層防護的分工</b>：
 * <ul>
 *   <li>{@code @RateLimiter}：單機整體限流，保護這台機器的執行緒池與連線池</li>
 *   <li>{@code @CircuitBreaker}：Redis／Kafka 故障時快速失敗，
 *       不讓請求執行緒全部卡在逾時等待上（這是雪崩的典型起點）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/seckill")
@Tag(name = "秒殺", description = "搶購與訂單查詢")
public class SeckillController {

    private static final Logger log = LoggerFactory.getLogger(SeckillController.class);
    private static final String RESILIENCE_INSTANCE = "seckill";

    private final SeckillUseCase seckillUseCase;
    private final OrderQueryUseCase orderQueryUseCase;

    public SeckillController(SeckillUseCase seckillUseCase, OrderQueryUseCase orderQueryUseCase) {
        this.seckillUseCase = seckillUseCase;
        this.orderQueryUseCase = orderQueryUseCase;
    }

    /**
     * 發起搶購。
     *
     * <p>回 <b>202 Accepted</b> 而非 201 Created：此刻訂單還沒建立，只是庫存預扣成功、
     * 建單訊息已投遞。用 201 會讓前端誤以為訂單已存在而立刻跳轉。
     * HTTP 狀態碼要誠實反映系統的真實狀態。
     */
    @PostMapping("/orders")
    @Operation(summary = "發起搶購", description = "庫存預扣成功後回傳訂單號，訂單由非同步流程建立")
    @RateLimiter(name = RESILIENCE_INSTANCE, fallbackMethod = "seckillFallback")
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "seckillFallback")
    public ResponseEntity<ApiResponse<SeckillTicket>> seckill(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody SeckillRequest request) {

        SeckillTicket ticket = seckillUseCase.attempt(request.toCommand(userId));
        return ResponseEntity.accepted().body(ApiResponse.ok(ticket));
    }

    /**
     * Resilience4j 的降級方法。
     *
     * <p>簽章必須與原方法一致，並在<b>最後</b>多一個 {@code Throwable} 參數。
     *
     * <p><b>業務例外必須原樣拋回</b>，否則「已售罄」會被降級成「系統繁忙」，
     * 使用者看到的錯誤訊息與真實原因完全脫節，客服與監控也會被誤導。
     * 降級只該處理基礎設施故障。
     */
    @SuppressWarnings("unused")
    private ResponseEntity<ApiResponse<SeckillTicket>> seckillFallback(
            Long userId, SeckillRequest request, Throwable throwable) {

        if (throwable instanceof BusinessException businessException) {
            throw businessException;
        }
        log.warn("搶購請求觸發降級 userId={}, activityId={}", userId, request.activityId(), throwable);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(ErrorCode.SYSTEM_BUSY, "系統繁忙，請稍後再試"));
    }

    @GetMapping("/orders/{orderNo}")
    @Operation(summary = "查詢訂單", description = "訂單仍在非同步建立中時回傳 PROCESSING，前端應繼續輪詢")
    public ApiResponse<OrderView> queryOrder(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable String orderNo) {

        return ApiResponse.ok(orderQueryUseCase.findByOrderNo(orderNo, userId));
    }
}
