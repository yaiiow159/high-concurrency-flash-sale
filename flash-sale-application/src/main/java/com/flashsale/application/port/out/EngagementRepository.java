package com.flashsale.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 收藏與瀏覽紀錄的持久化埠（出站）。 */
public interface EngagementRepository {

    /** 加入收藏。已收藏過不是錯誤，靠複合主鍵擋重複。 */
    void addToWishlist(Long userId, Long productId, Instant now);

    void removeFromWishlist(Long userId, Long productId);

    /** 我的收藏，新到舊。 */
    List<Long> findWishlistProductIds(Long userId, int limit, int offset);

    long countWishlist(Long userId);

    /** 這些商品裡哪些已收藏。商品列表用它一次問完，不必逐張卡片查。 */
    Set<Long> findWishlistedAmong(Long userId, List<Long> productIds);

    /** 記一次瀏覽。同一件商品只留最後一次。 */
    void recordView(Long userId, Long productId, Instant now);

    List<Long> findRecentlyViewed(Long userId, int limit);

    /**
     * 「看了這個的人也看了」。
     *
     * <p>依同時看過兩件商品的人數排序，排除商品自己。
     */
    List<Long> findAlsoViewed(Long productId, int limit);
}
