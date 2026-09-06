package com.flashsale.application.service;

import com.flashsale.application.port.in.NotificationDispatchUseCase;
import com.flashsale.application.port.out.NotificationRepository;
import com.flashsale.domain.aftersales.event.RefundRequestedEvent;
import com.flashsale.domain.notification.Notification;
import com.flashsale.domain.notification.NotificationChannel;
import com.flashsale.domain.notification.NotificationType;
import com.flashsale.domain.order.event.OrderCancelledEvent;
import com.flashsale.domain.order.event.OrderCompletedEvent;
import com.flashsale.domain.order.event.OrderPaidEvent;
import com.flashsale.domain.order.event.OrderShippedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** 由領域事件產生通知。 */
@Service
public class NotificationDispatchService implements NotificationDispatchUseCase {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);

    /** 事件超過這個時間就不再通知。 */
    private static final Duration MAX_EVENT_AGE = Duration.ofDays(1);

    private final NotificationRepository notificationRepository;
    private final NotificationComposer composer;
    private final Clock clock;

    public NotificationDispatchService(NotificationRepository notificationRepository,
                                       NotificationComposer composer,
                                       Clock clock) {
        this.notificationRepository = notificationRepository;
        this.composer = composer;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void onOrderPaid(OrderPaidEvent event) {
        dispatch(event.eventId(), event.userId(), NotificationType.ORDER_PAID,
                event.orderNo(), event.totalAmount(), event.occurredAt());
    }

    @Override
    @Transactional
    public void onOrderShipped(OrderShippedEvent event) {
        dispatch(event.eventId(), event.userId(), NotificationType.ORDER_SHIPPED,
                event.orderNo(), null, event.occurredAt());
    }

    @Override
    @Transactional
    public void onOrderCompleted(OrderCompletedEvent event) {
        dispatch(event.eventId(), event.userId(), NotificationType.ORDER_COMPLETED,
                event.orderNo(), null, event.occurredAt());
    }

    @Override
    @Transactional
    public void onOrderCancelled(OrderCancelledEvent event) {
        dispatch(event.eventId(), event.userId(), NotificationType.ORDER_CANCELLED,
                event.orderNo(), null, event.occurredAt());
    }

    @Override
    @Transactional
    public void onRefundRequested(RefundRequestedEvent event) {
        // 關聯的是退貨單號而非訂單號：使用者收到這封通知後想看的是那張退貨單
        dispatch(event.eventId(), event.userId(), NotificationType.REFUND_SENT,
                event.returnNo(), event.refundAmount(), event.occurredAt());
    }

    private void dispatch(String eventId, Long userId, NotificationType type,
                          String referenceNo, BigDecimal amount, Instant occurredAt) {
        Instant now = clock.instant();
        if (isStale(occurredAt, now)) {
            // 這通常代表消費組重播了歷史事件。安靜略過而不是拋例外——
            // 那是完全正常的情況，不是錯誤
            log.debug("事件 {} 發生於 {}，已超過通知窗口，略過", eventId, occurredAt);
            return;
        }

        NotificationComposer.Content content = composer.compose(type, referenceNo, amount);

        for (NotificationChannel channel : NotificationChannel.values()) {
            Notification notification = Notification.compose(userId, channel, type,
                    content.title(), content.body(), referenceNo, eventId, now);

            // 回 empty 代表這個事件在這個管道已經建立過通知了。
            // 那是重複投遞，不是錯誤——直接略過，不要拋例外讓訊息進 DLQ
            Optional<Notification> created = notificationRepository.saveIfAbsent(notification);
            if (created.isEmpty()) {
                log.debug("事件 {} 的 {} 通知已存在，略過", eventId, channel);
            }
        }
        log.debug("已產生通知 type={}, ref={}, userId={}", type, referenceNo, userId);
    }

    /** 事件是否已經過期。 */
    private boolean isStale(Instant occurredAt, Instant now) {
        return occurredAt != null && occurredAt.isBefore(now.minus(MAX_EVENT_AGE));
    }
}
