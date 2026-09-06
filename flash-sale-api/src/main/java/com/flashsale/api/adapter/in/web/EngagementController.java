package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.EngagementUseCase;
import com.flashsale.application.port.in.dto.ProductView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** 收藏、瀏覽紀錄與「看了又看」。 */
@RestController
@Tag(name = "收藏與瀏覽", description = "願望清單、最近看過、相關推薦")
public class EngagementController {

    private final EngagementUseCase engagement;

    public EngagementController(EngagementUseCase engagement) {
        this.engagement = engagement;
    }

    @PostMapping("/api/v1/wishlist/{productId}")
    @Operation(summary = "加入收藏", description = "重複收藏不是錯誤")
    public ApiResponse<Void> add(@CurrentUser Long userId, @PathVariable Long productId) {
        engagement.addToWishlist(userId, productId);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/api/v1/wishlist/{productId}")
    @Operation(summary = "取消收藏")
    public ApiResponse<Void> remove(@CurrentUser Long userId, @PathVariable Long productId) {
        engagement.removeFromWishlist(userId, productId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/v1/wishlist")
    @Operation(summary = "我的收藏", description = "新到舊；已下架的商品不會出現")
    public ApiResponse<WishlistPage> list(@CurrentUser Long userId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(new WishlistPage(
                engagement.wishlist(userId, page, size), engagement.wishlistCount(userId)));
    }

    /** 商品列表一次問完哪些已收藏，不必每張卡片各查一次。 */
    @GetMapping("/api/v1/wishlist/among")
    @Operation(summary = "批次查是否已收藏")
    public ApiResponse<Set<Long>> among(@CurrentUser Long userId,
                                        @RequestParam List<Long> productIds) {
        return ApiResponse.ok(engagement.wishlistedAmong(userId, productIds));
    }

    @PostMapping("/api/v1/products/{productId}/view")
    @Operation(summary = "記一次瀏覽", description = "同一件商品只留最後一次")
    public ApiResponse<Void> recordView(@CurrentUser Long userId, @PathVariable Long productId) {
        engagement.recordView(userId, productId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/v1/products/recently-viewed")
    @Operation(summary = "最近看過")
    public ApiResponse<List<ProductView>> recentlyViewed(
            @CurrentUser Long userId, @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(engagement.recentlyViewed(userId, limit));
    }

    /** 匿名可讀：它不含身分，而它出現在商品頁上——那一頁本身就是可快取的。 */
    @GetMapping("/api/v1/catalog/products/{productId}/also-viewed")
    @Operation(summary = "看了這個的人也看了", description = "資料不足時回空清單")
    public ApiResponse<List<ProductView>> alsoViewed(
            @PathVariable Long productId, @RequestParam(defaultValue = "8") int limit) {
        return ApiResponse.ok(engagement.alsoViewed(productId, limit));
    }

    public record WishlistPage(List<ProductView> items, long total) {
    }
}
