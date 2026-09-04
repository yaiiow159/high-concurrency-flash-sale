package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.MembershipUseCase;
import com.flashsale.application.port.in.dto.ExchangeableCouponView;
import com.flashsale.application.port.in.dto.MemberProfileView;
import com.flashsale.application.port.in.dto.PointTransactionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 會員中心。
 *
 * <p><b>每一支都以令牌裡的使用者為準，沒有任何 userId 參數。</b>
 * 積分是資產，讓呼叫端指定要看誰的餘額等於讓它看別人的錢包；
 * 而讓它指定要幫誰兌換，更是直接把別人的積分花掉。
 */
@RestController
@RequestMapping("/api/v1/membership")
@Tag(name = "會員", description = "積分、等級與兌換")
public class MembershipController {

    /** 流水一頁最多幾筆。上限由後端夾住，前端傳什麼都不算數。 */
    private static final int MAX_PAGE_SIZE = 50;

    private final MembershipUseCase membershipUseCase;

    public MembershipController(MembershipUseCase membershipUseCase) {
        this.membershipUseCase = membershipUseCase;
    }

    @GetMapping("/profile")
    @Operation(summary = "我的會員資料", description = "等級、積分餘額與升級進度")
    public ApiResponse<MemberProfileView> profile(@CurrentUser Long userId) {
        return ApiResponse.ok(membershipUseCase.profile(userId));
    }

    @GetMapping("/points")
    @Operation(summary = "積分流水", description = "新到舊；頁大小上限 50")
    public ApiResponse<List<PointTransactionView>> points(
            @CurrentUser Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ApiResponse.ok(membershipUseCase.transactions(userId,
                Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }

    @GetMapping("/exchange")
    @Operation(summary = "可兌換的優惠券", description = "並標示目前餘額換不換得起")
    public ApiResponse<List<ExchangeableCouponView>> exchangeable(@CurrentUser Long userId) {
        return ApiResponse.ok(membershipUseCase.exchangeableCoupons(userId));
    }

    /**
     * 兌換優惠券。
     *
     * <p>積分唯一的用途。直接折抵訂單會讓退款變成兩種資產的組合，
     * 而現在的退款路徑只認得錢（ADR-0016 決策 7）。
     *
     * <p><b>沒有冪等鍵。</b> 這是刻意的：兌換不是「同一個動作重送」，
     * 而是「我要再換一張」——兩次點擊產生兩張券是<b>正確</b>的行為，
     * 前提是點數真的夠。與下單那種「重送不該變成兩單」的語意相反。
     */
    @PostMapping("/exchange/{promotionId}")
    @Operation(summary = "兌換優惠券", description = "扣點與發券同一個交易；點數不足則整筆失敗")
    public ApiResponse<MembershipUseCase.ExchangeResult> exchange(
            @CurrentUser Long userId,
            @PathVariable Long promotionId) {

        return ApiResponse.ok(membershipUseCase.exchangeForCoupon(userId, promotionId));
    }
}
