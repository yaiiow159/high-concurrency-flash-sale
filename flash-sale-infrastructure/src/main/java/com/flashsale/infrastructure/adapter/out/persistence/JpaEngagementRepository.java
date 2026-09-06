package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.EngagementRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 收藏與瀏覽紀錄的持久化。 */
@Repository
public class JpaEngagementRepository implements EngagementRepository {

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
     * 看了這個的人也看了。
     *
     * <p>自連接找出「同時看過兩件商品」的人數，依人數排序。
     *
     * <p><b>限制在最近看過這件商品的人</b>：不設限的話這個 join 會隨紀錄無限成長，
     * 而三年前看過的人跟現在的相關性也很低。
     */
    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Long> findAlsoViewed(Long productId, int limit) {
        List<Number> ids = entityManager.createNativeQuery("""
                        select other.product_id
                        from (select user_id from browsing_history
                              where product_id = :productId
                              order by viewed_at desc limit 500) seed
                        join browsing_history other on other.user_id = seed.user_id
                        join product p on p.id = other.product_id and p.status = 'ON_SHELF'
                        where other.product_id <> :productId
                        group by other.product_id
                        order by count(*) desc, other.product_id desc
                        limit :limit
                        """)
                .setParameter("productId", productId)
                .setParameter("limit", limit)
                .getResultList();
        return ids.stream().map(Number::longValue).toList();
    }
}
