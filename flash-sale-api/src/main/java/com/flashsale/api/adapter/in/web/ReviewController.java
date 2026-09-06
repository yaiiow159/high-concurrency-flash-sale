package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.dto.WriteReviewRequest;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.RatingReconciliationUseCase;
import com.flashsale.application.port.in.dto.RatingReconciliation;
import com.flashsale.application.port.in.ReviewUseCase;
import com.flashsale.application.port.in.dto.ProductRatingView;
import com.flashsale.application.port.in.dto.ReviewView;
import com.flashsale.application.port.in.dto.ReviewableView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 商品評價。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "評價", description = "商品評價與評分")
public class ReviewController {

    /** 一頁的評價數上限。沒有上限的話，一個請求就能把整張表撈出來。 */
    private static final int MAX_PAGE_SIZE = 50;

    /** 批次評分一次最多幾件。沒有上限的話，一個請求就能把整張聚合表撈出來。 */
    private static final int MAX_BATCH_SIZE = 100;

    private final ReviewUseCase reviewUseCase;
    private final RatingReconciliationUseCase ratingReconciliationUseCase;

    public ReviewController(ReviewUseCase reviewUseCase,
                            RatingReconciliationUseCase ratingReconciliationUseCase) {
        this.reviewUseCase = reviewUseCase;
        this.ratingReconciliationUseCase = ratingReconciliationUseCase;
    }

    /** 商品的評分摘要。公開——沒登入的訪客也要看得到，那正是評價存在的意義。 */
    @GetMapping("/catalog/products/{productId}/rating")
    @SecurityRequirements
    @Operation(summary = "商品評分摘要", description = "平均分、則數與星等分佈；公開")
    public ApiResponse<ProductRatingView> rating(@PathVariable Long productId) {
        return ApiResponse.ok(reviewUseCase.ratingOf(productId));
    }

    /** 批次取多個商品的評分，供商品列表使用。 */
    @GetMapping("/catalog/products/ratings")
    @SecurityRequirements
    @Operation(summary = "批次商品評分", description = "供商品列表顯示星等；最多 100 件；公開")
    public ApiResponse<Map<Long, ProductRatingView>> ratings(
            @RequestParam List<Long> productIds) {

        List<Long> capped = productIds.size() > MAX_BATCH_SIZE
                ? productIds.subList(0, MAX_BATCH_SIZE)
                : productIds;
        return ApiResponse.ok(reviewUseCase.ratingsOf(capped));
    }

    @GetMapping("/catalog/products/{productId}/reviews")
    @SecurityRequirements
    @Operation(summary = "商品評價列表", description = "新到舊；頁大小上限 50；公開")
    public ApiResponse<List<ReviewView>> byProduct(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        return ApiResponse.ok(reviewUseCase.byProduct(productId,
                Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }

    /** 這張訂單現在能評什麼。 */
    @GetMapping("/orders/{orderNo}/reviewable")
    @Operation(summary = "訂單可評價項目", description = "哪幾項還沒評價；不能評時附上原因")
    public ApiResponse<ReviewableView> reviewable(@PathVariable String orderNo,
                                                  @CurrentUser Long userId) {
        return ApiResponse.ok(reviewUseCase.reviewable(orderNo, userId));
    }

    /** 發表評價。 */
    @PostMapping("/orders/{orderNo}/reviews")
    @Operation(summary = "發表評價", description = "訂單須為 COMPLETED，且該項尚未評價")
    public ResponseEntity<ApiResponse<ReviewView>> write(
            @PathVariable String orderNo,
            @Valid @RequestBody WriteReviewRequest request,
            @CurrentUser Long userId) {

        ReviewView review = reviewUseCase.write(new ReviewUseCase.WriteReviewCommand(
                userId, orderNo, request.skuId(), request.stars(), request.content()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(review));
    }

    /** 修改評價。發表後七天內有效，逾期回 B0046。 */
    @PutMapping("/reviews/{reviewId}")
    @Operation(summary = "修改評價", description = "發表後七天內可改；只能改自己的")
    public ApiResponse<ReviewView> edit(
            @PathVariable Long reviewId,
            @Valid @RequestBody WriteReviewRequest request,
            @CurrentUser Long userId) {

        return ApiResponse.ok(reviewUseCase.edit(new ReviewUseCase.EditReviewCommand(
                userId, reviewId, request.stars(), request.content())));
    }

    /** 評分聚合對帳。 */
    @GetMapping("/admin/reviews/reconciliation")
    @Operation(summary = "評分聚合對帳",
            description = "比對聚合與評價表；repair=true 時重算不一致的商品")
    public ApiResponse<RatingReconciliation> reconcile(
            @RequestParam(defaultValue = "false") boolean repair) {
        return ApiResponse.ok(ratingReconciliationUseCase.reconcile(repair));
    }

    @GetMapping("/reviews/mine")
    @Operation(summary = "我的評價", description = "新到舊；頁大小上限 50")
    public ApiResponse<List<ReviewView>> mine(
            @CurrentUser Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ApiResponse.ok(reviewUseCase.mine(userId,
                Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }
}
