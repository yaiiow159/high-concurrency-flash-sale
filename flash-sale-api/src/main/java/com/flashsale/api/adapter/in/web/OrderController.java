package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.dto.PlaceOrderRequest;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.OrderQueryUseCase;
import com.flashsale.application.port.in.PlaceOrderUseCase;
import com.flashsale.application.port.in.dto.OrderView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 一般下單 API。
 *
 * <p>與 {@link SeckillController} 的 HTTP 語意刻意不同，這是雙通道設計的外顯（ADR-0006）：
 *
 * <table border="1">
 *   <caption>兩條通道的 HTTP 契約</caption>
 *   <tr><th></th><th>一般下單</th><th>秒殺</th></tr>
 *   <tr><td>成功狀態碼</td><td>{@code 201 Created}</td><td>{@code 202 Accepted}</td></tr>
 *   <tr><td>回應內容</td><td>完整訂單，已成立</td><td>受理憑證，還要輪詢</td></tr>
 *   <tr><td>失敗時機</td><td>當下就知道</td><td>之後輪詢才知道</td></tr>
 * </table>
 *
 * <p><b>202 與 201 的差別不是風格問題。</b>202 的意思是「收到了，還沒做」，
 * 秒殺回它是誠實的——訂單真的還沒建立。一般下單回 201 也是誠實的，
 * 因為交易已經提交，訂單確實存在了。把兩者統一成同一個狀態碼，
 * 就是對其中一邊說謊。
 *
 * <p>這裡<b>不掛限流與斷路器</b>，也是刻意的：一般下單沒有單一熱點，
 * 流量分散在數萬個 SKU 上；套用秒殺那套嚴格限流只會誤傷正常購物。
 * 保護手段應該對應真實的風險形狀。
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "訂單", description = "一般下單與訂單查詢")
public class OrderController {

    private final PlaceOrderUseCase placeOrderUseCase;
    private final OrderQueryUseCase orderQueryUseCase;

    public OrderController(PlaceOrderUseCase placeOrderUseCase,
                           OrderQueryUseCase orderQueryUseCase) {
        this.placeOrderUseCase = placeOrderUseCase;
        this.orderQueryUseCase = orderQueryUseCase;
    }

    /**
     * 下單。
     *
     * <p>同步完成：回應時庫存已扣、訂單已成立，接著就可以付款。
     * 任何一個品項庫存不足，整筆訂單都不會成立——
     * 這是單一交易帶來的免費保證，不需要任何補償邏輯。
     */
    @PostMapping
    @Operation(summary = "下單", description = "同步建立訂單；任一品項庫存不足則整筆失敗")
    public ResponseEntity<ApiResponse<OrderView>> place(
            @Valid @RequestBody PlaceOrderRequest request,
            @CurrentUser Long userId) {

        OrderView order = placeOrderUseCase.place(request.toCommand(userId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(order));
    }

    @GetMapping("/{orderNo}")
    @Operation(summary = "查詢訂單")
    public ApiResponse<OrderView> findByOrderNo(@PathVariable String orderNo,
                                                @CurrentUser Long userId) {
        return ApiResponse.ok(orderQueryUseCase.findByOrderNo(orderNo, userId));
    }
}
