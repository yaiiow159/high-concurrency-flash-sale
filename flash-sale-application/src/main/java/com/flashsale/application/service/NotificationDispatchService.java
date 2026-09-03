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

/**
 * 由領域事件產生通知。
 *
 * <h2>兩個管道各建一筆，且各自獨立冪等</h2>
 *
 * <p>冪等鍵是 {@code (sourceEventId, channel)}。分管道而不是只看事件 ID，
 * 是因為兩者可能有一邊成功一邊失敗——若共用一個鍵，
 * Email 那筆重試時會被站內信的存在擋掉，然後永遠寄不出去。
 *
 * <h2>這裡不寄信</h2>
 *
 * <p>只把 Email 寫成 {@code PENDING}，實際寄送交給
 * {@code NotificationDeliveryScheduler}。理由與退款打金流相同：
 * SMTP 是遠端呼叫，留在交易裡會把資料庫交易的存活時間綁在對方的回應時間上。
 *
 * <p>而且這裡是 MQ 消費端——寄信失敗若讓整個消費失敗，
 * 一個信箱掛掉的使用者會拖住整個分區的通知。
 *
 * <h2>過期的事件不通知</h2>
 *
 * <p>實機第一次啟動時踩到：消費組是新的、{@code auto-offset-reset} 是
 * {@code earliest}，於是它把 topic 上<b>整段歷史</b>重播了一遍——
 * 1.6 秒內產生 80 筆通知，內容是幾個月前就已經送達的訂單。
 *
 * <p>這不是設定錯誤，而是「新消費組加入既有 topic」的正常行為，
 * 也會在正式環境的每一次「新增通知管道」或「重設消費位移」時重現。
 * 因此防線放在<b>應用層</b>而不是 Kafka 設定：
 * 設定會被改、環境之間也不一致，而「三個月前的出貨通知是雜訊不是資訊」
 * 這個判斷在任何設定下都成立。
 */
@Service
public class NotificationDispatchService implements NotificationDispatchUseCase {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);

    /**
     * 事件超過這個時間就不再通知。
     *
     * <p>取一天而不是幾分鐘：正常的投遞延遲是毫秒級，但 Kafka 積壓、
     * 消費端停機維護、DLQ 重投都可能讓一個<b>真的該通知</b>的事件晚幾小時才到。
     * 窗口太窄會把那些一起濾掉，而那才是真正會被客訴的漏通知。
     *
     * <p>取一天而不是一週：出貨通知晚三天到達已經沒有用了——
     * 使用者要嘛已經收到貨，要嘛早就自己去查了。
     */
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

    /**
     * 事件是否已經過期。
     *
     * <p>{@code occurredAt} 為 null 時<b>視為未過期</b>（照樣通知）。
     * 反過來預設成「過期」的話，一個欄位漏填就會讓所有通知安靜消失，
     * 而那種故障沒有任何症狀可循。
     */
    private boolean isStale(Instant occurredAt, Instant now) {
        return occurredAt != null && occurredAt.isBefore(now.minus(MAX_EVENT_AGE));
    }
}
