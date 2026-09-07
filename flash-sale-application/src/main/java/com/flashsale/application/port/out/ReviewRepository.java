package com.flashsale.application.port.out;

import com.flashsale.domain.review.ProductRating;
import com.flashsale.domain.review.Rating;
import com.flashsale.domain.review.Review;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 評價與評分聚合的持久化埠（出站）。 */
public interface ReviewRepository {

    /** 建立一則評價。 */
    Optional<Review> saveIfAbsent(Review review);

    Optional<Review> findById(Long reviewId);

    Optional<Review> findByOrderAndSku(String orderNo, Long skuId);

    /** 商品的評價列表，新到舊。 */
    List<Review> findByProductId(Long productId, int limit, int offset);

    /** 使用者寫過的評價，新到舊。 */
    List<Review> findByUserId(Long userId, int limit, int offset);

    /** 這張訂單上已經評價過哪些 SKU——畫面要標出哪幾項還能評。 */
    List<Long> findReviewedSkuIds(String orderNo);

    void update(Review review);

    /** 新增一則評分到聚合。 */
    void addRating(Long productId, Rating rating);

    /** 把一則評分換成另一則。 */
    void replaceRating(Long productId, Rating oldRating, Rating newRating);

    /** 沒有評價的商品回 {@link ProductRating#empty}，不回空 Optional——畫面要顯示「尚無評價」。 */
    ProductRating findRating(Long productId);

    /** 批次取多個商品的評分，供商品列表使用。 */
    Map<Long, ProductRating> findRatings(List<Long> productIds);

    /** 對帳：聚合與 {@code review} 表的真實統計不符的商品。 */
    List<RatingDrift> findRatingDrifts();

    /** 把某個商品的聚合重算成 {@code review} 表的真實統計。 */
    void recomputeRating(Long productId);

    /** @param storedCount 聚合上的快照；{@code actualCount} 是從 review 表數出來的真實值 */
    record RatingDrift(Long productId, long actualCount, long actualSum,
                       long storedCount, long storedSum) {
    }
}
