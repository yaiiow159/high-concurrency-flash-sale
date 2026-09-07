package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.application.port.in.OrderAdminUseCase;
import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.application.port.in.dto.PageView;
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
import java.util.HashMap;
import java.util.Map;

/** 後台訂單管理。整段 /api/v1/admin/** 由 SecurityConfig 要求 admin scope。 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@Tag(name = "訂單管理", description = "查單、看明細、手動關單")
public class OrderAdminController {

    private final OrderAdminUseCase orders;

    public OrderAdminController(OrderAdminUseCase orders) {
        this.orders = orders;
    }

    @GetMapping
    @Operation(summary = "搜尋訂單", description = "訂單號、使用者、狀態三個條件 AND；新到舊")
    public ApiResponse<PageView<OrderView>> search(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(orders.search(orderNo, userId, status, page, size));
    }

    @GetMapping("/{orderNo}")
    @Operation(summary = "訂單明細")
    public ApiResponse<OrderView> find(@PathVariable String orderNo) {
        return ApiResponse.ok(orders.find(orderNo));
    }

    @GetMapping("/{orderNo}/trace")
    @Operation(summary = "訂單的 trace id", description = "供後台跳轉 Tempo；沒有上游 trace 的單回 null")
    public ApiResponse<Map<String, String>> trace(@PathVariable String orderNo) {
        Map<String, String> body = new HashMap<>();
        body.put("traceId", orders.traceId(orderNo).orElse(null));
        return ApiResponse.ok(body);
    }

    @PostMapping("/{orderNo}/close")
    @Operation(summary = "手動關單", description = "只有待付款可關；會退庫存")
    public ApiResponse<OrderView> close(@PathVariable String orderNo,
                                        @Valid @RequestBody CloseRequest request) {
        return ApiResponse.ok(orders.close(orderNo, request.reason()));
    }

    public record CloseRequest(
            @NotBlank(message = "關單原因不可為空")
            @Size(max = 100, message = "關單原因不可超過 100 字")
            String reason) {
    }
}
