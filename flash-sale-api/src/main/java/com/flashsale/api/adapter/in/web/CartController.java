package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.dto.CartMergeRequest;
import com.flashsale.api.adapter.in.web.dto.CartQuantityRequest;
import com.flashsale.api.adapter.in.web.dto.CartRequest;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.CartUseCase;
import com.flashsale.application.port.in.dto.CartView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 購物車 API。
 *
 * <p>全部需要登入。未登入的購物車放在前端 localStorage，
 * 登入後呼叫一次 {@code /merge} 併進來——這讓「先逛再登入」成為可能，
 * 而不是逼使用者一進站就登入。
 *
 * <p><b>每個操作都回傳完整的購物車</b>，而不是只回傳被改動的那一項。
 * 購物車的總額、可購買狀態會因為單一品項的變動而改變
 * （某個商品剛好下架、價格剛好調整），只回傳差異會讓前端自己拼湊出
 * 一份與伺服器不一致的狀態。多傳幾百個位元組，換掉整類同步問題。
 *
 * <p>所有回應的價格都是<b>當下</b>的目錄價，僅供預覽。
 * 真正的金額在下單時重新計算並凍結進訂單。
 */
@RestController
@RequestMapping("/api/v1/cart")
@Tag(name = "購物車", description = "購物車管理與本地購物車合併")
public class CartController {

    private final CartUseCase cartUseCase;

    public CartController(CartUseCase cartUseCase) {
        this.cartUseCase = cartUseCase;
    }

    @GetMapping
    @Operation(summary = "查看購物車", description = "價格為當下目錄價，僅供預覽")
    public ApiResponse<CartView> view(@CurrentUser Long userId) {
        return ApiResponse.ok(cartUseCase.view(userId));
    }

    @PostMapping("/items")
    @Operation(summary = "加入購物車", description = "同一個 SKU 會累加數量，不會新增一行")
    public ApiResponse<CartView> addItem(@Valid @RequestBody CartRequest request,
                                         @CurrentUser Long userId) {
        return ApiResponse.ok(cartUseCase.addItem(userId, request.skuId(), request.quantity()));
    }

    @PutMapping("/items/{skuId}")
    @Operation(summary = "調整數量", description = "設為 0 等同移除")
    public ApiResponse<CartView> changeQuantity(@PathVariable Long skuId,
                                                @Valid @RequestBody CartQuantityRequest request,
                                                @CurrentUser Long userId) {
        return ApiResponse.ok(cartUseCase.changeQuantity(userId, skuId, request.quantity()));
    }

    @DeleteMapping("/items/{skuId}")
    @Operation(summary = "移除品項")
    public ApiResponse<CartView> removeItem(@PathVariable Long skuId,
                                            @CurrentUser Long userId) {
        return ApiResponse.ok(cartUseCase.removeItem(userId, skuId));
    }

    @DeleteMapping
    @Operation(summary = "清空購物車")
    public ResponseEntity<Void> clear(@CurrentUser Long userId) {
        cartUseCase.clear(userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 登入後合併本地購物車。
     *
     * <p>同一個 SKU 取兩邊較大值而非相加：在手機加了 2 件、電腦也加了 2 件的人，
     * 想要的幾乎一定是 2 件；相加會讓他在結帳頁看到一個從沒按過的數字 4。
     */
    @PostMapping("/merge")
    @Operation(summary = "合併本地購物車", description = "同一 SKU 取兩邊較大值；不可購買的品項會被略過")
    public ApiResponse<CartView> merge(@Valid @RequestBody CartMergeRequest request,
                                       @CurrentUser Long userId) {
        return ApiResponse.ok(cartUseCase.merge(userId, request.toLocalItems()));
    }
}
