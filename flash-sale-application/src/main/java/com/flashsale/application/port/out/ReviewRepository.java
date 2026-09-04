package com.flashsale.application.port.out;

import com.flashsale.domain.review.ProductRating;
import com.flashsale.domain.review.Rating;
import com.flashsale.domain.review.Review;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 評價與評分聚合的持久化埠（出站）。 */
public interface ReviewRepository {

    /**
     * 建立一則評價。
     *
     * <p>回 {@link Optional#empty()} 代表<b>唯一索引擋下了重複</b>——
     * 這筆訂單行已經被評價過。回空而不是拋例外，是因為呼叫端在這裡
     * 需要的是「有沒有成功」這個事實，而不是一個堆疊。
     * 與 {@code OrderRepository.saveIfAbsent} 同一個形狀。
     */
    Optional<Review> saveIfAbsent(Review review);

    Optional<Review> findById(Long reviewId);

    Optional<Review> findByOrderAndSku(String orderNo, Long skuId);

    /** 商品的評價列表，新到舊。 */
    List<Review> findByProductId(Long productId, int offset, int limit);

    /** 使用者寫過的評價，新到舊。 */
    List<Review> findByUserId(Long userId, int offset, int limit);

    /** 這張訂單上已經評價過哪些 SKU——畫面要標出哪幾項還能評。 */
    List<Long> findReviewedSkuIds(String orderNo);

    void update(Review review);

    /**
     * 新增一則評分到聚合。
     *
     * <p><b>必須是條件式增量 UPDATE</b>（ADR-0014 決策 3）：
     *
     * <pre>
     *   UPDATE product_rating
     *      SET rating_sum = rating_sum + ?, rating_count = rating_count + 1, count_n = count_n + 1
     *    WHERE product_id = ?
     * </pre>
     *
     * <p>不要 SELECT 出來在 Java 裡加完再 UPDATE——那是 read-modify-write，
     * 兩個人同時評價同一件商品時會有一則被吃掉。
     * 與庫存扣減、券的核銷同一個手法，這是本專案第三次用它。
     *
     * <p>受影響列數為 0 只代表這個商品還沒有聚合列（不是錯誤），
     * 實作要補一列再重試。
     */
    void addRating(Long productId, Rating rating);

    /**
     * 把一則評分換成另一則。
     *
     * <p>{@code ratingCount} 不變，只動 {@code rating_sum} 與兩個分佈桶。
     * 這是這裡唯一容易寫錯的地方——把它寫成「先移除再新增」會讓
     * 中間有一瞬間的 count 少一，而那一瞬間剛好有人讀到就會看到錯的平均分。
     *
     * @param oldRating 資料庫裡當場讀出來的舊評分，<b>不可由呼叫端宣告</b>——
     *                  呼叫端若能指定舊評分，它就能把商品的平均分改成任何值
     */
    void replaceRating(Long productId, Rating oldRating, Rating newRating);

    /** 沒有評價的商品回 {@link ProductRating#empty}，不回空 Optional——畫面要顯示「尚無評價」。 */
    ProductRating findRating(Long productId);

    /**
     * 批次取多個商品的評分，供商品列表使用。
     *
     * <p>存在的唯一理由是避免 N+1：列表一頁 24 件商品，
     * 逐件查就是 24 次往返。回傳的 Map <b>只包含有評價的商品</b>，
     * 呼叫端對缺席的鍵用 {@code ProductRating.empty} 補上。
     */
    Map<Long, ProductRating> findRatings(List<Long> productIds);

    /**
     * 對帳：聚合與 {@code review} 表的真實統計不符的商品。
     *
     * <p>包含兩種偏差：聚合數字對不上，以及<b>有評價卻連聚合列都沒有</b>。
     * 後者在商品頁上會顯示「尚無評價」——評價明明存在卻完全看不到。
     */
    List<RatingDrift> findRatingDrifts();

    /**
     * 把某個商品的聚合重算成 {@code review} 表的真實統計。
     *
     * <p>這是全篇唯一一處「設成」而非「加上去」的寫入，
     * 而它之所以安全，正是因為它不做增量：來源是 review 表的當下統計，
     * 不依賴聚合的舊值。
     */
    void recomputeRating(Long productId);

    /** @param storedCount 聚合上的快照；{@code actualCount} 是從 review 表數出來的真實值 */
    record RatingDrift(Long productId, long actualCount, long actualSum,
                       long storedCount, long storedSum) {
    }
}
