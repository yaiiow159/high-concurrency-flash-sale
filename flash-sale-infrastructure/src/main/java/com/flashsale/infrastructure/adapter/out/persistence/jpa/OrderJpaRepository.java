package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.OrderEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 訂單的 Spring Data 介面。 */
public interface OrderJpaRepository extends JpaRepository<OrderEntity, Long> {

    /** 以 {@code @EntityGraph} 一次撈出訂單行。 */
    @EntityGraph(attributePaths = "lines")
    Optional<OrderEntity> findByOrderNo(String orderNo);

    /** 悲觀寫鎖，用於序列化同一張訂單的退貨額度計算。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderEntity o where o.orderNo = :orderNo")
    Optional<OrderEntity> findByOrderNoForUpdate(@Param("orderNo") String orderNo);

    @EntityGraph(attributePaths = "lines")
    Optional<OrderEntity> findByRequestId(String requestId);

    boolean existsByRequestId(String requestId);

    /** 撈取逾期未付款訂單。 */
    @EntityGraph(attributePaths = "lines")
    @Query("""
            select o from OrderEntity o
            where o.status = 'PENDING_PAYMENT' and o.createdAt < :deadline
            order by o.createdAt asc
            """)
    List<OrderEntity> findExpiredPending(@Param("deadline") Instant deadline, Limit limit);

    /** 統計某活動仍被佔用的數量。 */
    @Query("""
            select coalesce(sum(l.quantity), 0)
            from OrderLineEntity l join l.order o
            where l.sourceActivityId = :activityId
              and o.status in ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'COMPLETED', 'REFUNDED')
            """)
    long sumActiveQuantityByActivity(@Param("activityId") Long activityId);

    /** 某使用者的訂單，新到舊。 */
    @EntityGraph(attributePaths = "lines")
    List<OrderEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** 我的訂單，可依狀態篩選。 */
    @Query("""
            select o from OrderEntity o
            where o.userId = :userId
              and (:status is null or o.status = :status)
            order by o.createdAt desc
            """)
    List<OrderEntity> findByUserIdAndStatus(@Param("userId") Long userId,
                                            @Param("status") String status,
                                            Pageable pageable);

    /** 批次查詢存在的訂單號，供對帳比對孤兒扣減。 */
    @Query("select o.orderNo from OrderEntity o where o.orderNo in :orderNos")
    List<String> findExistingOrderNos(@Param("orderNos") Collection<String> orderNos);
}
