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

    /** 買家的退貨列表。兩段式，理由見 {@link #findStuckRefundNos}。 */
    @Query("""
            select r.returnNo from ReturnRequestEntity r
            where r.userId = :userId
            order by r.createdAt desc
            """)
    List<String> findReturnNosByUser(@Param("userId") Long userId, Pageable pageable);

    /** 客服後台的待審清單。同樣兩段式。 */
    @Query("""
            select r.returnNo from ReturnRequestEntity r
            where r.status = :status
            order by r.createdAt asc
            """)
    List<String> findReturnNosByStatus(@Param("status") String status, Limit limit);

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

    @EntityGraph(attributePaths = "lines")
    List<ReturnRequestEntity> findByReturnNoInOrderByCreatedAtDesc(Collection<String> returnNos);

    @EntityGraph(attributePaths = "lines")
    List<ReturnRequestEntity> findByReturnNoInOrderByCreatedAtAsc(Collection<String> returnNos);

    long countByStatus(String status);
}
