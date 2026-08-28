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
}
