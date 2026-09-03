package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.NotificationRepository;
import com.flashsale.domain.notification.Notification;
import com.flashsale.domain.notification.NotificationChannel;
import com.flashsale.domain.notification.NotificationStatus;
import com.flashsale.domain.notification.NotificationType;
import com.flashsale.infrastructure.adapter.out.persistence.entity.NotificationEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.NotificationJpaRepository;
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
     * <p>用資料庫的 upsert 而不是「先查、再寫、接住衝突」——
     * 後者在加入外層交易時，唯一索引衝突會把整個交易標記成 rollback-only，
     * 而站內信與 Email 是同一個迴圈裡的兩次寫入：Email 撞衝突會連
     * 站內信那筆一起回滾。詳見 {@code NotificationJpaRepository.insertIfAbsent}。
     */
    @Override
    @Transactional
    public Optional<Notification> saveIfAbsent(Notification notification) {
        String channel = notification.channel().name();
        int inserted = jpaRepository.insertIfAbsent(
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
        if (inserted == 0) {
            return Optional.empty();
        }
        // 重讀一次是為了把資料庫產生的 id 帶回領域物件。
        // 直接回傳傳入的那個會少了 id，而後續要更新它時就找不到紀錄
        return jpaRepository.findBySourceEventIdAndChannel(notification.sourceEventId(), channel)
                .map(JpaNotificationRepository::toDomain);
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
                        PageRequest.of(offset / Math.max(limit, 1), limit)).stream()
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
    public List<Notification> findUnreadInApp(Long userId, int limit) {
        return jpaRepository.findByUserIdAndChannelAndReadAtIsNullOrderByCreatedAtAsc(
                        userId, NotificationChannel.IN_APP.name(), Limit.of(limit)).stream()
                .map(JpaNotificationRepository::toDomain)
                .toList();
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
