package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.dto.WriteReviewRequest;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
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

/**
 * 商品評價。
 *
 * <p><b>讀是公開的，寫必須認證。</b> 評價列表與評分摘要要能被沒登入的訪客看到——
 * 那正是評價存在的意義（幫還沒買的人做決定）。
 * 而「誰能寫」是這個功能的全部價值所在，因此寫入這一側有三道實質約束
 * （訂單擁有、訂單已完成、一行只評一次）。
 *
 * <p>公開的兩支掛在<b>商品的正規路徑</b>下（{@code /api/v1/catalog/products/{id}/...}）
 * 而不是自立一個 {@code /api/v1/products} 前綴：同一個資源有兩個路徑前綴，
 * 呼叫端就得記兩套規則，而那種不一致沒有任何好處。
 *
 * <p>它們在 {@code SecurityConfig} 裡<b>逐一列出</b>，即使
 * {@code /api/v1/catalog/**} 的 GET 規則已經涵蓋。冗餘的放行條目是無害的，
 * 而缺口不是——那條 catalog 規則哪天被收緊，評價會安靜地變成需要登入。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "評價", description = "商品評價與評分")
public class ReviewController {

    /** 一頁的評價數上限。沒有上限的話，一個請求就能把整張表撈出來。 */
    private static final int MAX_PAGE_SIZE = 50;

    /** 批次評分一次最多幾件。沒有上限的話，一個請求就能把整張聚合表撈出來。 */
    private static final int MAX_BATCH_SIZE = 100;

    private final ReviewUseCase reviewUseCase;

    public ReviewController(ReviewUseCase reviewUseCase) {
        this.reviewUseCase = reviewUseCase;
    }

    /** 商品的評分摘要。公開——沒登入的訪客也要看得到，那正是評價存在的意義。 */
    @GetMapping("/catalog/products/{productId}/rating")
    @SecurityRequirements
    @Operation(summary = "商品評分摘要", description = "平均分、則數與星等分佈；公開")
    public ApiResponse<ProductRatingView> rating(@PathVariable Long productId) {
        return ApiResponse.ok(reviewUseCase.ratingOf(productId));
    }

    /**
     * 批次取多個商品的評分，供商品列表使用。
     *
     * <p>存在的唯一理由是避免 N+1：列表一頁 24 件商品，逐件查就是 24 次往返。
     *
     * <p><b>用查詢參數而不是 POST 一個 body</b>：這是純讀取，
     * 而 POST 會讓它無法被任何一層快取。上限 100 是為了避免
     * 一個請求把整張聚合表撈出來——超過的部分直接截斷而不是報錯，
     * 因為呼叫端要的是「畫面上這幾件商品的星等」，少幾件不該讓整頁掛掉。
     */
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

    /**
     * 這張訂單現在能評什麼。
     *
     * <p>由後端算而不是讓前端比對訂單行與既有評價——
     * 前端再實作一次的話，症狀會是「畫面說可以評，送出卻被拒絕」。
     */
    @GetMapping("/orders/{orderNo}/reviewable")
    @Operation(summary = "訂單可評價項目", description = "哪幾項還沒評價；不能評時附上原因")
    public ApiResponse<ReviewableView> reviewable(@PathVariable String orderNo,
                                                  @CurrentUser Long userId) {
        return ApiResponse.ok(reviewUseCase.reviewable(orderNo, userId));
    }

    /**
     * 發表評價。
     *
     * <p>回 {@code 201}：一則評價真的被建立了。
     * 使用者 ID 來自令牌，不來自請求內容——讓呼叫端自己宣告身分，
     * 等於讓任何人以任何人的名義發表評價。
     */
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
