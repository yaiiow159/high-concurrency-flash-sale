package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.dto.CheckoutRequest;
import com.flashsale.api.adapter.in.web.dto.CheckoutPreviewRequest;
import com.flashsale.api.adapter.in.web.dto.PlaceOrderRequest;
import com.flashsale.api.adapter.in.web.dto.PreviewOrderRequest;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.CheckoutUseCase;
import com.flashsale.application.port.in.OrderQueryUseCase;
import com.flashsale.application.port.in.PlaceOrderUseCase;
import com.flashsale.application.port.in.dto.CheckoutPreview;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 一般下單 API。 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "訂單", description = "一般下單與訂單查詢")
public class OrderController {

    private final PlaceOrderUseCase placeOrderUseCase;
    private final CheckoutUseCase checkoutUseCase;
    private final OrderQueryUseCase orderQueryUseCase;

    public OrderController(PlaceOrderUseCase placeOrderUseCase,
                           CheckoutUseCase checkoutUseCase,
                           OrderQueryUseCase orderQueryUseCase) {
        this.placeOrderUseCase = placeOrderUseCase;
        this.checkoutUseCase = checkoutUseCase;
        this.orderQueryUseCase = orderQueryUseCase;
    }

    /** 下單。 */
    @PostMapping
    @Operation(summary = "下單", description = "同步建立訂單；任一品項庫存不足則整筆失敗")
    public ResponseEntity<ApiResponse<OrderView>> place(
            @Valid @RequestBody PlaceOrderRequest request,
            @CurrentUser Long userId) {

        OrderView order = placeOrderUseCase.place(request.toCommand(userId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(order));
    }

    /** 從購物車結帳。 */
    @PostMapping("/checkout")
    @Operation(summary = "購物車結帳", description = "品項取自伺服器端購物車；成功後清空購物車")
    public ResponseEntity<ApiResponse<OrderView>> checkout(
            @Valid @RequestBody CheckoutRequest request,
            @CurrentUser Long userId) {

        OrderView order = checkoutUseCase.checkout(userId, request.requestId(),
                request.addressId(), request.couponId(), request.shippingMethod(),
                request.buyerNote());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(order));
    }

    /** 結帳試算。 */
    @PostMapping("/preview")
    @Operation(summary = "結帳試算", description = "不建訂單、不扣庫存、不核銷券")
    public ApiResponse<CheckoutPreview> preview(
            @Valid @RequestBody PreviewOrderRequest request,
            @CurrentUser Long userId) {

        return ApiResponse.ok(placeOrderUseCase.preview(request.toCommand(userId)));
    }

    /** 購物車結帳試算。 */
    @PostMapping("/checkout/preview")
    @Operation(summary = "購物車結帳試算", description = "品項取自購物車；不建訂單、不核銷券")
    public ApiResponse<CheckoutPreview> checkoutPreview(
            @RequestBody(required = false) CheckoutPreviewRequest request,
            @CurrentUser Long userId) {

        Long couponId = request == null ? null : request.couponId();
        Long addressId = request == null ? null : request.addressId();
        return ApiResponse.ok(checkoutUseCase.preview(userId, couponId, addressId));
    }

    @GetMapping
    @Operation(summary = "我的訂單",
            description = "新到舊；頁大小上限 50；status 可篩選單一狀態（不帶為全部）")
    public ApiResponse<List<OrderView>> listForUser(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser Long userId) {

        return ApiResponse.ok(orderQueryUseCase.listForUser(userId, status, page, size));
    }

    @GetMapping("/{orderNo}")
    @Operation(summary = "查詢訂單")
    public ApiResponse<OrderView> findByOrderNo(@PathVariable String orderNo,
                                                @CurrentUser Long userId) {
        return ApiResponse.ok(orderQueryUseCase.findByOrderNo(orderNo, userId));
    }
}
