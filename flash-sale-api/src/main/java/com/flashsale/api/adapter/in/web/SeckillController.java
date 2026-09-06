package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.dto.SeckillRequest;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 搶購 API。 */
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

    /** 發起搶購。 */
    @PostMapping("/orders")
    @Operation(summary = "發起搶購", description = "庫存預扣成功後回傳訂單號，訂單由非同步流程建立")
    @RateLimiter(name = RESILIENCE_INSTANCE, fallbackMethod = "seckillFallback")
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "seckillFallback")
    public ResponseEntity<ApiResponse<SeckillTicket>> seckill(
            @CurrentUser Long userId,
            @Valid @RequestBody SeckillRequest request) {

        SeckillTicket ticket = seckillUseCase.attempt(request.toCommand(userId));
        return ResponseEntity.accepted().body(ApiResponse.ok(ticket));
    }

    /** Resilience4j 的降級方法。 */
    public ResponseEntity<ApiResponse<SeckillTicket>> seckillFallback(
            Long userId, SeckillRequest request, Throwable throwable) {

        if (throwable instanceof BusinessException businessException) {
            throw businessException;
        }
        // **不印堆疊。** 熔斷器打開時每一個請求都會走到這裡，
        // 而每筆一份堆疊會讓降級路徑比正常路徑還貴——
        // 系統已經在麻煩裡了，日誌 I/O 不該再補一刀。
        // 實測：12 秒的壓測跑出 96 萬行日誌、1 萬份堆疊，
        // 而那 1 萬份講的是同一件事。
        // 真正的細節在 resilience4j 的指標裡，那才是該看的地方
        log.warn("搶購請求觸發降級 userId={}, activityId={}, 原因={}",
                userId, request.activityId(), throwable.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(ErrorCode.SYSTEM_BUSY, "系統繁忙，請稍後再試"));
    }

    @GetMapping("/orders/{orderNo}")
    @Operation(summary = "查詢訂單", description = "訂單仍在非同步建立中時回傳 PROCESSING，前端應繼續輪詢")
    public ApiResponse<OrderView> queryOrder(
            @CurrentUser Long userId,
            @PathVariable String orderNo) {

        return ApiResponse.ok(orderQueryUseCase.findByOrderNo(orderNo, userId));
    }
}
