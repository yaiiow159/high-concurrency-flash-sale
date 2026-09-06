package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.NotificationEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 通知的 Spring Data 介面。 */
public interface NotificationJpaRepository extends JpaRepository<NotificationEntity, Long> {

    Optional<NotificationEntity> findBySourceEventIdAndChannel(String sourceEventId, String channel);

    /** 寫入一筆通知；來源事件在這個管道已有紀錄時什麼都不做。 */
    @Modifying
    @Query(value = """
            INSERT INTO notification
                (user_id, channel, type, title, body, reference_no, source_event_id,
                 status, attempt_count, created_at, sent_at, version)
            VALUES
                (:userId, :channel, :type, :title, :body, :referenceNo, :sourceEventId,
                 :status, 0, :createdAt, :sentAt, 0)
            ON DUPLICATE KEY UPDATE id = id
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId,
                       @Param("channel") String channel,
                       @Param("type") String type,
                       @Param("title") String title,
                       @Param("body") String body,
                       @Param("referenceNo") String referenceNo,
                       @Param("sourceEventId") String sourceEventId,
                       @Param("status") String status,
                       @Param("createdAt") Instant createdAt,
                       @Param("sentAt") Instant sentAt);

    List<NotificationEntity> findByUserIdAndChannelOrderByCreatedAtDesc(
            Long userId, String channel, Pageable pageable);

    long countByUserIdAndChannelAndReadAtIsNull(Long userId, String channel);

    /** 未讀的站內信，舊到新——先發生的先標記，順序與使用者的閱讀直覺一致。 */
    List<NotificationEntity> findByUserIdAndChannelAndReadAtIsNullOrderByCreatedAtAsc(
            Long userId, String channel, Limit limit);

    /** 撈取待寄送的通知。 */
    @Query("""
            select n from NotificationEntity n
            where n.channel = :channel
              and n.status in ('PENDING', 'FAILED')
              and n.attemptCount < :maxAttempts
            order by n.createdAt asc
            """)
    List<NotificationEntity> findAwaitingDelivery(@Param("channel") String channel,
                                                  @Param("maxAttempts") int maxAttempts,
                                                  Limit limit);
}
