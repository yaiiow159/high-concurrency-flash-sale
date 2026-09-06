package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.RestockSubscriptionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 到貨通知訂閱的持久化。 */
@Repository
public class JpaRestockSubscriptionRepository implements RestockSubscriptionRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 訂閱。
     *
     * <p>單句 upsert，靠 {@code uk_restock_pending} 擋重複（V29）。
     *
     * <p>先前寫成 {@code insert ... where not exists}，因為當時的唯一鍵含
     * {@code notified_at}，而 NULL 不會觸發衝突。那個寫法在 REPEATABLE READ 下
     * 會取 gap lock——<b>實測連點四次會有兩次 deadlock</b>，使用者看到的是
     * 一顆按了沒反應的按鈕。
     */
    @Override
    @Transactional
    public void subscribe(Long userId, Long skuId, Instant now) {
        entityManager.createNativeQuery("""
                        insert into restock_subscription (user_id, sku_id, created_at)
                        values (:userId, :skuId, :now)
                        on duplicate key update created_at = created_at
                        """)
                .setParameter("userId", userId)
                .setParameter("skuId", skuId)
                .setParameter("now", Timestamp.from(now))
                .executeUpdate();
    }

    @Override
    @Transactional
    public void unsubscribe(Long userId, Long skuId) {
        entityManager.createNativeQuery("""
                        delete from restock_subscription
                        where user_id = :userId and sku_id = :skuId and notified_at is null
                        """)
                .setParameter("userId", userId)
                .setParameter("skuId", skuId)
                .executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Long> findPendingSkuIds(Long userId) {
        List<Number> ids = entityManager.createNativeQuery("""
                        select sku_id from restock_subscription
                        where user_id = :userId and notified_at is null
                        """)
                .setParameter("userId", userId)
                .getResultList();
        return ids.stream().map(Number::longValue).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countPending(Long userId) {
        return ((Number) entityManager.createNativeQuery("""
                        select count(*) from restock_subscription
                        where user_id = :userId and notified_at is null
                        """)
                .setParameter("userId", userId)
                .getSingleResult()).longValue();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Long> findRestockedSkuIds(int limit) {
        List<Number> ids = entityManager.createNativeQuery("""
                        select distinct s.sku_id from restock_subscription s
                        join inventory i on i.sku_id = s.sku_id and i.available > 0
                        where s.notified_at is null
                        limit :limit
                        """)
                .setParameter("limit", limit)
                .getResultList();
        return ids.stream().map(Number::longValue).toList();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Pending> findWaitersFor(Long skuId, int limit) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select id, user_id from restock_subscription
                        where sku_id = :skuId and notified_at is null
                        order by created_at asc limit :limit
                        """)
                .setParameter("skuId", skuId)
                .setParameter("limit", limit)
                .getResultList();
        return rows.stream()
                .map(row -> new Pending(((Number) row[0]).longValue(),
                        ((Number) row[1]).longValue()))
                .toList();
    }

    /**
     * 標記已通知。
     *
     * <p>{@code notified_at is null} 這個條件不可省：它讓「標記」變成一次
     * 條件式 UPDATE，兩個節點同時補貨時只有一個會拿到非零的更新筆數，
     * 另一個看到 0 就知道別人先做了——不需要另外一把鎖。
     */
    @Override
    @Transactional
    public int markNotified(List<Long> subscriptionIds, Instant now) {
        if (subscriptionIds.isEmpty()) {
            return 0;
        }
        return entityManager.createNativeQuery("""
                        update restock_subscription set notified_at = :now
                        where id in (:ids) and notified_at is null
                        """)
                .setParameter("ids", subscriptionIds)
                .setParameter("now", Timestamp.from(now))
                .executeUpdate();
    }
}
