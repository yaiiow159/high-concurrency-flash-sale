package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.NotificationRepository;
import com.flashsale.domain.notification.Notification;
import com.flashsale.domain.notification.NotificationChannel;
import com.flashsale.domain.notification.NotificationStatus;
import com.flashsale.domain.notification.NotificationType;
import com.flashsale.infrastructure.adapter.out.persistence.entity.NotificationEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.NotificationJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 通知持久化轉接器。 */
@Repository
public class JpaNotificationRepository implements NotificationRepository {

    private final NotificationJpaRepository jpaRepository;

    public JpaNotificationRepository(NotificationJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>先查再寫，並且接住唯一索引的衝突。</b>先查是為了讓絕大多數的重複
     * 走便宜的路徑；接住衝突是因為兩個消費者實例可能同時處理同一個事件，
     * 那時兩邊都會查不到、都嘗試寫入，而輸的那一方要當作「已存在」而非錯誤。
     *
     * <p>與 {@code JpaOrderRepository.saveIfAbsent} 同一個手法。
     */
    @Override
    @Transactional
    public Optional<Notification> saveIfAbsent(Notification notification) {
        String channel = notification.channel().name();
        if (jpaRepository.findBySourceEventIdAndChannel(
                notification.sourceEventId(), channel).isPresent()) {
            return Optional.empty();
        }

        NotificationEntity entity = new NotificationEntity(
                notification.userId(),
                channel,
                notification.type().name(),
                notification.title(),
                notification.body(),
                notification.referenceNo(),
                notification.sourceEventId(),
                notification.status().name(),
                notification.createdAt(),
                notification.sentAt());
        try {
            return Optional.of(toDomain(jpaRepository.saveAndFlush(entity)));
        } catch (DataIntegrityViolationException e) {
            // 另一個實例剛好搶先寫入。這是重複投遞，不是錯誤
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public Notification update(Notification notification) {
        NotificationEntity entity = jpaRepository.findById(notification.id())
                .orElseThrow(() -> new IllegalStateException(
                        "更新通知時找不到紀錄 id=" + notification.id()));
        entity.applyStateChange(notification.status().name(), notification.recipient(),
                notification.failureReason(), notification.sentAt(), notification.readAt(),
                notification.attemptCount());
        return toDomain(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Notification> findById(Long id) {
        return jpaRepository.findById(id).map(JpaNotificationRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Notification> findInAppByUserId(Long userId, int limit, int offset) {
        return jpaRepository.findByUserIdAndChannelOrderByCreatedAtDesc(
                        userId, NotificationChannel.IN_APP.name(),
                        PageRequest.of(offset / limit, limit)).stream()
                .map(JpaNotificationRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnread(Long userId) {
        return jpaRepository.countByUserIdAndChannelAndReadAtIsNull(
                userId, NotificationChannel.IN_APP.name());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Notification> findAwaitingDelivery(NotificationChannel channel,
                                                   int maxAttempts, int limit) {
        return jpaRepository.findAwaitingDelivery(channel.name(), maxAttempts, Limit.of(limit))
                .stream()
                .map(JpaNotificationRepository::toDomain)
                .toList();
    }

    private static Notification toDomain(NotificationEntity entity) {
        return Notification.restore(
                entity.getId(),
                entity.getUserId(),
                NotificationChannel.valueOf(entity.getChannel()),
                NotificationType.valueOf(entity.getType()),
                entity.getTitle(),
                entity.getBody(),
                entity.getReferenceNo(),
                entity.getSourceEventId(),
                NotificationStatus.valueOf(entity.getStatus()),
                entity.getRecipient(),
                entity.getFailureReason(),
                entity.getCreatedAt(),
                entity.getSentAt(),
                entity.getReadAt(),
                entity.getAttemptCount(),
                entity.getVersion());
    }
}
