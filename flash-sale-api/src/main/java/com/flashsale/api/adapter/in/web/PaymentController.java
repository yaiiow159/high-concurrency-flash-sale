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

    /**
     * 為訂單發起付款。
     *
     * <p>回傳的 {@code paymentUrl} 供前端導向金流頁面。
     * <b>前端不可把這個回應當成「已付款」</b>——真正的結果由閘道回調決定，
     * 前端應輪詢訂單狀態。
     */
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

    /**
     * 金流閘道回調。
     *
     * <p><b>這是整個系統唯一對外開放的寫入端點。</b> 它必須匿名——
     * 金流閘道不會帶著使用者的令牌打過來——因此安全性完全建立在<b>簽章驗證</b>上。
     * 少了那一步，任何人送一個「付款成功」就能免費下單。
     *
     * <p>回 200 即代表「已收到並處理」。閘道通常以此判斷是否要重送；
     * 回非 2xx 會觸發重送，而重送是安全的（處理邏輯冪等）。
     */
    @PostMapping("/payments/callback")
    @SecurityRequirements
    @Operation(summary = "金流回調", description = "由金流閘道呼叫；以簽章驗證來源，處理邏輯冪等")
    public ResponseEntity<Void> handleCallback(@RequestBody Map<String, String> parameters) {
        paymentUseCase.handleGatewayCallback(parameters);
        return ResponseEntity.ok().build();
    }
}
