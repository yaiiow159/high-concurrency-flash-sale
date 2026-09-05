package com.flashsale.api.adapter.in.web;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import com.flashsale.application.port.in.dto.ClaimableCouponView;
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
import java.util.Map;

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
    @GetMapping("/claimable")
    @Operation(summary = "領券中心",
            description = "進行中、可以領的券。已領過的仍會回傳並標記 claimed")
    public ApiResponse<List<ClaimableCouponView>> claimable(@CurrentUser Long userId) {
        return ApiResponse.ok(couponQueryUseCase.claimable(userId));
    }

    /**
     * 領一張券。
     *
     * <p>回 <b>200 而不是 201</b>：重複領取不是錯誤，是預期中的結果
     * （使用者連點兩下、兩個分頁各按一次）。
     * 回 409 會讓前端得為一個正常操作寫錯誤處理，
     * 而使用者要的答案兩種情況都一樣——「這張券在你手上了」。
     */
    @PostMapping("/{promotionId}/claim")
    @Operation(summary = "領取優惠券", description = "一人一張；重複領取回 claimed=false 而非錯誤")
    public ApiResponse<Map<String, Object>> claim(@PathVariable Long promotionId,
                                                  @CurrentUser Long userId) {
        boolean claimed = couponQueryUseCase.claim(userId, promotionId);
        return ApiResponse.ok(Map.of("promotionId", promotionId, "claimed", claimed));
    }

}
