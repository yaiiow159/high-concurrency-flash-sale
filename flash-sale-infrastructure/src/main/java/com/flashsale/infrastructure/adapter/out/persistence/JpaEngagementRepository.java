package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.EngagementRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 收藏與瀏覽紀錄的持久化。 */
@Repository
public class JpaEngagementRepository implements EngagementRepository {

    /**
     * 要多少人同時看過才算一組推薦。
     *
     * <p>3 是 k-匿名的下限：低於它，這個匿名端點就等於在公開個別使用者的瀏覽紀錄。
     */
    private static final int MIN_CO_VIEWERS = 3;

    /**
     * 只看這段期間內的瀏覽。
     *
     * <p>`limit 500` 只綁住了 seed 那一側，另一側是「這 500 個人看過的全部商品」——
     * 而瀏覽紀錄沒有淘汰機制，重度使用者累積幾千列之後那個 join 會炸開。
     * 而且三個月前一起看過的相關性本來就低。
     */
    private static final Duration CO_VIEW_WINDOW = Duration.ofDays(90);

    /**
     * 聚合階段多取幾倍當緩衝。
     *
     * <p>{@code join product} 移到聚合之後，是為了不要對中間結果的每一列
     * 各做一次主鍵查找——那可能是上百萬次，只為了留下 8 列。
     * 代價是下架商品會佔掉名額，所以多取一些再過濾。
     */
    private static final int DOWN_SHELF_BUFFER = 5;

    /** 瀏覽紀錄保留多久。與「看了又看」的時間窗一致，超過的本來就用不到。 */
    private static final Duration RETENTION = Duration.ofDays(90);

    /** 單批刪除上限。一次刪幾十萬列會鎖很久，而沒清完的明天還在。 */
    private static final int PURGE_BATCH = 50_000;

    @PersistenceContext
    private EntityManager entityManager;

    /** upsert 而不是先查再插：重複收藏是常態（連點兩下愛心），不該拋例外。 */
    @Override
    @Transactional
    public void addToWishlist(Long userId, Long productId, Instant now) {
        entityManager.createNativeQuery("""
                        insert into wishlist_item (user_id, product_id, created_at)
                        values (:userId, :productId, :now)
                        on duplicate key update created_at = created_at
                        """)
                .setParameter("userId", userId)
                .setParameter("productId", productId)
                .setParameter("now", Timestamp.from(now))
                .executeUpdate();
    }

    @Override
    @Transactional
    public void removeFromWishlist(Long userId, Long productId) {
        entityManager.createNativeQuery(
                        "delete from wishlist_item where user_id = :userId and product_id = :productId")
                .setParameter("userId", userId)
                .setParameter("productId", productId)
                .executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Long> findWishlistProductIds(Long userId, int limit, int offset) {
        List<Number> ids = entityManager.createNativeQuery("""
                        select product_id from wishlist_item where user_id = :userId
                        order by created_at desc, product_id desc limit :limit offset :offset
                        """)
                .setParameter("userId", userId)
                .setParameter("limit", limit)
                .setParameter("offset", offset)
                .getResultList();
        return ids.stream().map(Number::longValue).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countWishlist(Long userId) {
        return ((Number) entityManager.createNativeQuery(
                        "select count(*) from wishlist_item where user_id = :userId")
                .setParameter("userId", userId)
                .getSingleResult()).longValue();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Set<Long> findWishlistedAmong(Long userId, List<Long> productIds) {
        List<Number> ids = entityManager.createNativeQuery("""
                        select product_id from wishlist_item
                        where user_id = :userId and product_id in (:productIds)
                        """)
                .setParameter("userId", userId)
                .setParameter("productIds", productIds)
                .getResultList();
        Set<Long> result = new HashSet<>();
        ids.forEach(id -> result.add(id.longValue()));
        return result;
    }

    /** 同一件商品只留最後一次瀏覽，否則「最近看過」會被同一件商品洗版。 */
    @Override
    @Transactional
    public void recordView(Long userId, Long productId, Instant now) {
        entityManager.createNativeQuery("""
                        insert into browsing_history (user_id, product_id, viewed_at)
                        values (:userId, :productId, :now)
                        on duplicate key update viewed_at = values(viewed_at)
                        """)
                .setParameter("userId", userId)
                .setParameter("productId", productId)
                .setParameter("now", Timestamp.from(now))
                .executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Long> findRecentlyViewed(Long userId, int limit) {
        List<Number> ids = entityManager.createNativeQuery("""
                        select product_id from browsing_history where user_id = :userId
                        order by viewed_at desc limit :limit
                        """)
                .setParameter("userId", userId)
                .setParameter("limit", limit)
                .getResultList();
        return ids.stream().map(Number::longValue).toList();
    }

    /**
     * 刪掉超過保留期的瀏覽紀錄。
     *
     * <p>單批上限：一次刪幾十萬列會鎖很久，而這是每天跑的清理，
     * 沒清完的明天還在。
     */
    @Override
    @Transactional
    public int purgeOldViews() {
        return entityManager.createNativeQuery(
                        "delete from browsing_history where viewed_at < :before limit :batch")
                .setParameter("before", Timestamp.from(Instant.now().minus(RETENTION)))
                .setParameter("batch", PURGE_BATCH)
                .executeUpdate();
    }

    /**
     * 看了這個的人也看了。
     *
     * <p>自連接找出「同時看過兩件商品」的人數，依人數排序。
     *
     * <p><b>限制在最近看過這件商品的人</b>：不設限的話這個 join 會隨紀錄無限成長，
     * 而三年前看過的人跟現在的相關性也很低。
     *
     * <p><b>至少要 {@value #MIN_CO_VIEWERS} 個人一起看過才輸出。</b>
     * 這是 k-匿名，不是效能措施——它在聚合之後才套用，一列工作量都不會省。
     * 這支端點是匿名可讀的，而少了這道門檻它就會變成一個查詢介面：
     * 知道某人看過某個冷門商品的人，可以直接問出他還看了什麼。
     * 實測過——只有一個人看過的商品組合會被完整吐出來。
     */
    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Long> findAlsoViewed(Long productId, int limit) {
        List<Number> ids = entityManager.createNativeQuery("""
                        select ranked.product_id from (
                            select other.product_id, count(*) viewers
                            from (select user_id from browsing_history
                                  where product_id = :productId
                                  order by viewed_at desc limit 500) seed
                            join browsing_history other on other.user_id = seed.user_id
                                 and other.viewed_at > :since
                            where other.product_id <> :productId
                            group by other.product_id
                            having count(*) >= :minViewers
                            order by count(*) desc, other.product_id desc
                            limit :buffer
                        ) ranked
                        join product p on p.id = ranked.product_id and p.status = 'ON_SHELF'
                        order by ranked.viewers desc, ranked.product_id desc
                        limit :limit
                        """)
                .setParameter("productId", productId)
                .setParameter("since", Timestamp.from(Instant.now().minus(CO_VIEW_WINDOW)))
                .setParameter("minViewers", MIN_CO_VIEWERS)
                .setParameter("buffer", limit * DOWN_SHELF_BUFFER)
                .setParameter("limit", limit)
                .getResultList();
        return ids.stream().map(Number::longValue).toList();
    }
}
