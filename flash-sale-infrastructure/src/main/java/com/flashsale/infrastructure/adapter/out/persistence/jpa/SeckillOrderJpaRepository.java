package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.SeckillOrderEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
}
