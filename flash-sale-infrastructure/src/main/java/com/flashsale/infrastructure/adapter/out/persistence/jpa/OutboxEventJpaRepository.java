package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.OutboxEventEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** 發件匣的 Spring Data 介面。 */
public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, Long> {

    @Query("""
            select e from OutboxEventEntity e
            where e.status = 'PENDING'
            order by e.id asc
            """)
    List<OutboxEventEntity> findPending(Limit limit);

    /**
     * 清理已投遞的舊紀錄。
     *
     * <p>Outbox 表寫入量等同訂單量，不清理會在幾次大促後變成效能瓶頸——
     * 每次 {@code findPending} 都得掃過越來越大的表。
     */
    @Modifying
    @Query("delete from OutboxEventEntity e where e.status = 'PUBLISHED' and e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
