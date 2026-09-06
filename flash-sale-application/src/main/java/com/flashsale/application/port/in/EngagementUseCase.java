package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ProductView;

import java.util.List;
import java.util.Set;

/** 收藏、瀏覽紀錄與由它推導出的推薦。 */
public interface EngagementUseCase {

    void addToWishlist(Long userId, Long productId);

    void removeFromWishlist(Long userId, Long productId);

    /** 我的收藏。已下架的商品不會出現——收藏頁不該列出點進去會 404 的東西。 */
    List<ProductView> wishlist(Long userId, int page, int size);

    long wishlistCount(Long userId);

    Set<Long> wishlistedAmong(Long userId, List<Long> productIds);

    /** 記一次瀏覽。未登入不記——沒有身分就沒有「我的」紀錄可言。 */
    void recordView(Long userId, Long productId);

    List<ProductView> recentlyViewed(Long userId, int limit);

    /** 看了這個的人也看了。資料不足時回空清單，由前端決定要不要顯示這一區。 */
    List<ProductView> alsoViewed(Long productId, int limit);
}
