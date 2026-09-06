package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.SeckillActivityEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** 活動的 Spring Data 介面；僅供基礎設施層內部使用，不外洩到應用層。 */
public interface SeckillActivityJpaRepository extends JpaRepository<SeckillActivityEntity, Long> {

    @Query("""
            select a from SeckillActivityEntity a
            where a.status = 'ONLINE' and a.endAt > :now
            order by a.startAt asc
            """)
    List<SeckillActivityEntity> findOnline(@Param("now") Instant now);

    /** 後台用：所有活動，含草稿與已下架。 */
    @Query("select a from SeckillActivityEntity a order by a.id desc")
    List<SeckillActivityEntity> findAllForAdmin(Pageable pageable);

    /** 需要對帳的活動：已上架，且結束時間仍在保留窗口內。 */
    @Query("""
            select a from SeckillActivityEntity a
            where a.status = 'ONLINE' and a.endAt > :endedAfter
            order by a.id asc
            """)
    List<SeckillActivityEntity> findForReconciliation(@Param("endedAfter") Instant endedAfter);

    /** 已完全冷卻、可以釋放庫存的活動。 */
    @Query("""
            select a from SeckillActivityEntity a
            where a.endAt <= :endedBefore
            order by a.id asc
            """)
    List<SeckillActivityEntity> findEndedBefore(@Param("endedBefore") Instant endedBefore);
}
