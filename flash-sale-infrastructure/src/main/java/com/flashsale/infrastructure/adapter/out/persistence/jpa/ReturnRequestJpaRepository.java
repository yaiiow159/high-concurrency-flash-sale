package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.ReturnRequestEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 退貨單的 Spring Data 介面。 */
public interface ReturnRequestJpaRepository extends JpaRepository<ReturnRequestEntity, Long> {

    /** 退貨行一律隨單帶出。 */
    @EntityGraph(attributePaths = "lines")
    Optional<ReturnRequestEntity> findByReturnNo(String returnNo);

    @EntityGraph(attributePaths = "lines")
    List<ReturnRequestEntity> findByOrderNo(String orderNo);

    @EntityGraph(attributePaths = "lines")
    Optional<ReturnRequestEntity> findByRequestId(String requestId);

    @EntityGraph(attributePaths = "lines")
    List<ReturnRequestEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = "lines")
    List<ReturnRequestEntity> findByStatusOrderByCreatedAtAsc(String status, Limit limit);

    /**
     * 卡住的退款單號。**刻意只取單號、不帶 {@code @EntityGraph}**——
     * join fetch 配上 limit 會讓 Hibernate 撈完整個結果集再於記憶體裡切
     * （HHH90003004），而閘道故障時這個集合是幾萬筆。
     */
    @Query("""
            select r.returnNo from ReturnRequestEntity r
            where r.status = :status and r.refundStartedAt < :startedBefore
            order by r.refundStartedAt""")
    List<String> findStuckRefundNos(@Param("status") String status,
                                    @Param("startedBefore") Instant startedBefore,
                                    Limit limit);

    @EntityGraph(attributePaths = "lines")
    List<ReturnRequestEntity> findByReturnNoInOrderByRefundStartedAtAsc(Collection<String> returnNos);

    long countByStatus(String status);
}
