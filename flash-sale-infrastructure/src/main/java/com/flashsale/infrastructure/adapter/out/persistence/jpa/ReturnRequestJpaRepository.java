package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.ReturnRequestEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
