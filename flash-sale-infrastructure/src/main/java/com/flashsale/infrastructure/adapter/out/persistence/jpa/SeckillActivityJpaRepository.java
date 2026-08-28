package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.SeckillActivityEntity;
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

    /**
     * 需要對帳的活動：已上架，且結束時間仍在保留窗口內。
     *
     * <p>刻意涵蓋剛結束的活動——庫存洩漏最常在活動尾聲才浮現。
     */
    @Query("""
            select a from SeckillActivityEntity a
            where a.status = 'ONLINE' and a.endAt > :endedAfter
            order by a.id asc
            """)
    List<SeckillActivityEntity> findForReconciliation(@Param("endedAfter") Instant endedAfter);
}
