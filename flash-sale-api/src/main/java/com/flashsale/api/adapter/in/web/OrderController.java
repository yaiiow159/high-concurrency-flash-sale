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
    private final CheckoutUseCase checkoutUseCase;
    private final OrderQueryUseCase orderQueryUseCase;

    public OrderController(PlaceOrderUseCase placeOrderUseCase,
                           CheckoutUseCase checkoutUseCase,
                           OrderQueryUseCase orderQueryUseCase) {
        this.placeOrderUseCase = placeOrderUseCase;
        this.checkoutUseCase = checkoutUseCase;
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

    /**
     * 從購物車結帳。
     *
     * <p>與直接下單共用同一條建立路徑，只是品項來自伺服器端的購物車。
     * 成功後購物車會在<b>同一個交易裡</b>清空——
     * 訂單建立了但購物車沒清，使用者會重複下單。
     */
    @PostMapping("/checkout")
    @Operation(summary = "購物車結帳", description = "品項取自伺服器端購物車；成功後清空購物車")
    public ResponseEntity<ApiResponse<OrderView>> checkout(
            @Valid @RequestBody CheckoutRequest request,
            @CurrentUser Long userId) {

        OrderView order = checkoutUseCase.checkout(userId, request.requestId(),
                request.addressId(), request.couponId(), request.shippingMethod());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(order));
    }

    /**
     * 結帳試算。
     *
     * <p><b>回 200 而不是 201：什麼都沒有被建立。</b>
     * 這條路徑不扣庫存、不核銷券，純粹回答「這樣買要付多少」。
     *
     * <p>存在的理由是使用者按下送出之前就該看到折扣。讓前端自己算是錯的——
     * 兩邊算出不同答案時，使用者只會相信他先看到的那一個。
     */
    @PostMapping("/preview")
    @Operation(summary = "結帳試算", description = "不建訂單、不扣庫存、不核銷券")
    public ApiResponse<CheckoutPreview> preview(
            @Valid @RequestBody PreviewOrderRequest request,
            @CurrentUser Long userId) {

        return ApiResponse.ok(placeOrderUseCase.preview(request.toCommand(userId)));
    }

    /**
     * 購物車結帳試算。
     *
     * <p>品項來自伺服器端的購物車，與 {@code /checkout} 同一個來源——
     * 試算若讓前端送品項，就會出現「試算的內容與真正下單的內容不同」。
     */
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
    @Operation(summary = "我的訂單", description = "新到舊；頁大小上限 50")
    public ApiResponse<List<OrderView>> listForUser(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser Long userId) {

        return ApiResponse.ok(orderQueryUseCase.listForUser(userId, page, size));
    }

    @GetMapping("/{orderNo}")
    @Operation(summary = "查詢訂單")
    public ApiResponse<OrderView> findByOrderNo(@PathVariable String orderNo,
                                                @CurrentUser Long userId) {
        return ApiResponse.ok(orderQueryUseCase.findByOrderNo(orderNo, userId));
    }
}
