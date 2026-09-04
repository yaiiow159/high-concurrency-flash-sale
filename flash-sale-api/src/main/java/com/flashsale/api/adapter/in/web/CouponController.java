package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.CouponQueryUseCase;
import com.flashsale.application.port.in.dto.CouponView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 優惠券查詢。
 *
 * <p><b>使用者 ID 來自令牌，不來自路徑或查詢參數。</b>
 * 讓呼叫端指定要看誰的券，等於讓它看別人的券。
 */
@RestController
@RequestMapping("/api/v1/coupons")
@Tag(name = "優惠券", description = "查詢自己手上可用的優惠券")
public class CouponController {

    private final CouponQueryUseCase couponQueryUseCase;

    public CouponController(CouponQueryUseCase couponQueryUseCase) {
        this.couponQueryUseCase = couponQueryUseCase;
    }

    @GetMapping
    @Operation(summary = "我的優惠券", description = "只回還沒用、也還沒過期的券")
    public ApiResponse<List<CouponView>> myCoupons(@CurrentUser Long userId) {
        return ApiResponse.ok(couponQueryUseCase.myUsableCoupons(userId));
    }
}
