package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.NotificationEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 通知的 Spring Data 介面。 */
public interface NotificationJpaRepository extends JpaRepository<NotificationEntity, Long> {

    Optional<NotificationEntity> findBySourceEventIdAndChannel(String sourceEventId, String channel);

    List<NotificationEntity> findByUserIdAndChannelOrderByCreatedAtDesc(
            Long userId, String channel, Pageable pageable);

    long countByUserIdAndChannelAndReadAtIsNull(Long userId, String channel);

    /**
     * 撈取待寄送的通知。
     *
     * <p><b>狀態清單含 {@code FAILED}</b>：寄信失敗最常見的成因是 SMTP 暫時故障，
     * 重試就會成功。只撈 {@code PENDING} 等於讓一次網路抖動永久吞掉一封通知。
     *
     * <p>{@code UNDELIVERABLE} 不在清單裡——那些是信箱不存在之類的永久失敗，
     * 撈進來每一輪都只是白試一次。
     *
     * <p>依 {@code createdAt} 排序：先發生的事先通知。
     * 讓「已送達」比「已出貨」先寄到，使用者會以為順序錯了。
     */
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
