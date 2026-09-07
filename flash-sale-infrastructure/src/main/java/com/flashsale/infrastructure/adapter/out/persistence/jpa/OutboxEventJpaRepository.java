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

    /** 清理已投遞的舊紀錄。 */
    @Modifying
    @Query("delete from OutboxEventEntity e where e.status = 'PUBLISHED' and e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);

    /**
     * 投遞已放棄的事件數。
     *
     * <p>DEAD 代表「這個事件永遠不會被投遞」，而下游可能是退庫、通知、積分——
     * 少了這個 gauge，它只會安靜地累積在表裡：清理排程只清 PUBLISHED，
     * 而告警規則的處置指引寫著「檢查 outbox_event 的 DEAD 紀錄」卻沒有東西看得到它。
     */
    long countByStatus(String status);
}
