package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.dto.DispatchRequest;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.FulfillmentUseCase;
import com.flashsale.application.port.in.dto.ShipmentView;
import com.flashsale.domain.fulfillment.ShipmentStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 履約 API。
 *
 * <p><b>刻意拆成兩段路徑</b>，因為它們的讀者完全不同：
 *
 * <ul>
 *   <li>{@code /api/v1/orders/{orderNo}/shipment} —— 買家查自己的出貨進度，
 *       以令牌的身分為界</li>
 *   <li>{@code /api/v1/admin/shipments/**} —— 營運端揀貨、出貨、更新配送狀態，
 *       需要 {@code seckill:admin} scope</li>
 * </ul>
 *
 * <p>把兩者放在同一個路徑下、靠參數區分權限，是最容易寫出漏洞的做法——
 * 少一個判斷，買家就能替自己的訂單標記「已送達」。
 */
@RestController
@Tag(name = "履約", description = "出貨、物流狀態與送達")
public class FulfillmentController {

    private final FulfillmentUseCase fulfillmentUseCase;

    public FulfillmentController(FulfillmentUseCase fulfillmentUseCase) {
        this.fulfillmentUseCase = fulfillmentUseCase;
    }

    @GetMapping("/api/v1/orders/{orderNo}/shipment")
    @Operation(summary = "查詢出貨進度", description = "只能查自己的訂單")
    public ApiResponse<ShipmentView> findForUser(@PathVariable String orderNo,
                                                 @CurrentUser Long userId) {
        return ApiResponse.ok(fulfillmentUseCase.findForUser(orderNo, userId));
    }

    @GetMapping("/api/v1/admin/shipments")
    @Operation(summary = "待處理出貨清單", description = "依狀態篩選，建立時間由舊到新")
    public ApiResponse<List<ShipmentView>> listByStatus(
            @RequestParam(defaultValue = "READY") ShipmentStatus status,
            @RequestParam(defaultValue = "50") int limit) {

        return ApiResponse.ok(fulfillmentUseCase.listByStatus(status, limit));
    }

    @PostMapping("/api/v1/admin/shipments/{orderNo}/dispatch")
    @Operation(summary = "出貨", description = "交付承運商並把訂單推進到已出貨；配送失敗後可再次呼叫以重新派送")
    public ApiResponse<ShipmentView> dispatch(@PathVariable String orderNo,
                                              @Valid @RequestBody DispatchRequest request) {
        return ApiResponse.ok(fulfillmentUseCase.dispatch(
                orderNo, request.carrier(), request.trackingNumber()));
    }

    @PostMapping("/api/v1/admin/shipments/{orderNo}/delivered")
    @Operation(summary = "標記送達", description = "訂單同步轉為完成；這是退貨期限的起算點")
    public ApiResponse<ShipmentView> markDelivered(@PathVariable String orderNo) {
        return ApiResponse.ok(fulfillmentUseCase.markDelivered(orderNo));
    }

    /**
     * 標記配送失敗。
     *
     * <p><b>不會改變訂單狀態</b>——失敗後幾乎都是重新派送，
     * 讓訂單狀態跟著來回跳動只會讓買家困惑，而他能做的事從頭到尾沒變。
     */
    @PostMapping("/api/v1/admin/shipments/{orderNo}/failed")
    @Operation(summary = "標記配送失敗", description = "不是終態；可再次呼叫 dispatch 重新派送")
    public ApiResponse<ShipmentView> markFailed(
            @PathVariable String orderNo,
            @RequestParam @NotBlank @Size(max = 256) String reason) {

        return ApiResponse.ok(fulfillmentUseCase.markFailed(orderNo, reason));
    }
}
