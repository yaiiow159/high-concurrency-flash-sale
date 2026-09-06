package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.PaymentUseCase;
import com.flashsale.application.port.in.dto.PaymentIntentView;
import com.flashsale.application.port.in.dto.PaymentView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 付款 API。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "付款", description = "發起付款與金流回調")
public class PaymentController {

    private final PaymentUseCase paymentUseCase;

    public PaymentController(PaymentUseCase paymentUseCase) {
        this.paymentUseCase = paymentUseCase;
    }

    /** 為訂單發起付款。 */
    @PostMapping("/orders/{orderNo}/payments")
    @Operation(summary = "發起付款", description = "回傳金流付款頁網址；結果由閘道回調決定")
    public ResponseEntity<ApiResponse<PaymentIntentView>> initiate(
            @CurrentUser Long userId,
            @PathVariable String orderNo) {

        PaymentIntentView intent = paymentUseCase.initiate(orderNo, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(intent));
    }

    @GetMapping("/orders/{orderNo}/payments")
    @Operation(summary = "查詢訂單的付款狀態")
    public ApiResponse<PaymentView> findByOrder(
            @CurrentUser Long userId,
            @PathVariable String orderNo) {

        return ApiResponse.ok(paymentUseCase.findByOrderNo(orderNo, userId));
    }

    /** 金流閘道回調。 */
    @PostMapping("/payments/callback")
    @SecurityRequirements
    @Operation(summary = "金流回調", description = "由金流閘道呼叫；以簽章驗證來源，處理邏輯冪等")
    public ResponseEntity<Void> handleCallback(@RequestBody Map<String, String> parameters) {
        paymentUseCase.handleGatewayCallback(parameters);
        return ResponseEntity.ok().build();
    }
}
