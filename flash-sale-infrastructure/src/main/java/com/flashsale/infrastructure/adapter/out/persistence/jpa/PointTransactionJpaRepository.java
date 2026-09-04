package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.PointTransactionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PointTransactionJpaRepository extends JpaRepository<PointTransactionEntity, Long> {

    @Query("""
            select t from PointTransactionEntity t
             where t.userId = :userId
             order by t.id desc
            """)
    List<PointTransactionEntity> findByUser(@Param("userId") Long userId, Pageable pageable);

    Optional<PointTransactionEntity> findByUserIdAndReasonAndRefNo(
            Long userId, String reason, String refNo);

    /**
     * 對帳用：流水加總。
     *
     * <p>餘額是快照、流水才是真實來源，因此「兩者不一致」是一個
     * 查得出來的問題——而查得出來的問題才有機會被修。
     */
    @Query("select coalesce(sum(t.delta), 0) from PointTransactionEntity t where t.userId = :userId")
    long sumDelta(@Param("userId") Long userId);
}
