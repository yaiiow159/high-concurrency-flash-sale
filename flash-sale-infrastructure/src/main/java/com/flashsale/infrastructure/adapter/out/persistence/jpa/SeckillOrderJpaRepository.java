package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.SeckillOrderEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 訂單的 Spring Data 介面。 */
public interface SeckillOrderJpaRepository extends JpaRepository<SeckillOrderEntity, Long> {

    Optional<SeckillOrderEntity> findByOrderNo(String orderNo);

    Optional<SeckillOrderEntity> findByRequestId(String requestId);

    boolean existsByRequestId(String requestId);

    /**
     * 撈取逾期未付款訂單。
     *
     * <p>走 {@code idx_status_created} 複合索引；{@code Limit} 讓 SQL 帶上 LIMIT 子句，
     * 避免尖峰後累積的大量待關訂單一次全撈進記憶體。
     */
    @Query("""
            select o from SeckillOrderEntity o
            where o.status = 'PENDING_PAYMENT' and o.createdAt < :deadline
            order by o.createdAt asc
            """)
    List<SeckillOrderEntity> findExpiredPending(@Param("deadline") Instant deadline, Limit limit);

    /**
     * 統計仍佔用庫存的訂單總量。
     *
     * <p>{@code coalesce} 不可省略：沒有任何訂單時 {@code sum} 回傳 null，
     * 拆箱成 long 會直接 NPE——而「活動剛開始還沒有訂單」正是最常見的情況。
     */
    @Query("""
            select coalesce(sum(o.quantity), 0) from SeckillOrderEntity o
            where o.activityId = :activityId and o.status in ('PENDING_PAYMENT', 'PAID')
            """)
    long sumActiveQuantity(@Param("activityId") Long activityId);

    /** 批次查詢存在的訂單號，供對帳比對孤兒扣減。 */
    @Query("select o.orderNo from SeckillOrderEntity o where o.orderNo in :orderNos")
    List<String> findExistingOrderNos(@Param("orderNos") Collection<String> orderNos);
}
