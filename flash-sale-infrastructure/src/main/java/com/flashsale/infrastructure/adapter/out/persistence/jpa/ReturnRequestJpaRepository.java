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

    /**
     * 退貨行一律隨單帶出。
     *
     * <p>退貨單的每一個用途——算金額、算可退數量、顯示——都要用到行，
     * 沒有一個情境是只要表頭的。讓它 LAZY 再處處 fetch 只是把 N+1 換個地方發生。
     */
    @EntityGraph(attributePaths = "lines")
    Optional<ReturnRequestEntity> findByReturnNo(String returnNo);

    @EntityGraph(attributePaths = "lines")
    List<ReturnRequestEntity> findByOrderNo(String orderNo);

    @EntityGraph(attributePaths = "lines")
    List<ReturnRequestEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = "lines")
    List<ReturnRequestEntity> findByStatusOrderByCreatedAtAsc(String status, Limit limit);
}
