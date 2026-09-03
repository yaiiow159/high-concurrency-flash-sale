package com.flashsale.infrastructure.adapter.in.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.application.port.in.NotificationDispatchUseCase;
import com.flashsale.domain.aftersales.event.RefundRequestedEvent;
import com.flashsale.domain.order.event.OrderCancelledEvent;
import com.flashsale.domain.order.event.OrderCompletedEvent;
import com.flashsale.domain.order.event.OrderPaidEvent;
import com.flashsale.domain.order.event.OrderShippedEvent;
import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * 把領域事件變成通知。
 *
 * <p>與 {@code FulfillmentConsumer}、{@code RefundConsumer} 共用同一個 topic
 * 但各自的 group，因此三邊都收到完整的事件流、互不影響。
 * 共用 group 的話一則事件只會被其中一個消費掉。
 *
 * <p><b>冪等由 {@code (sourceEventId, channel)} 的唯一索引保證。</b>
 * 重複投遞是常態不是異常，而重複的後果是使用者為同一次出貨收到三封一樣的信——
 * 那比漏寄更容易讓人乾脆關掉全部通知。
 *
 * <p>這裡只寫資料庫、不寄信。SMTP 是遠端呼叫，留在消費端會讓
 * 一個信箱掛掉的使用者拖住整個分區的通知（寄送交給
 * {@code NotificationDeliveryScheduler}）。
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final NotificationDispatchUseCase dispatchUseCase;
    private final ObjectMapper objectMapper;

    public NotificationConsumer(NotificationDispatchUseCase dispatchUseCase,
                                ObjectMapper objectMapper) {
        this.dispatchUseCase = dispatchUseCase;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_EVENT,
            groupId = "${flash-sale.mq.notification-group:notification-dispatcher}",
            concurrency = "${flash-sale.mq.notification-concurrency:2}")
    public void onDomainEvent(@Payload String payload,
                              @Header(name = KafkaTopics.HEADER_EVENT_TYPE, required = false)
                              String eventType) throws Exception {
        if (eventType == null) {
            return;
        }

        // 用 switch 而非一串 if-instanceof：新增一個要通知的事件型別時，
        // 這裡是唯一要改的地方，而漏掉的那些會落到 default 被安靜略過
        // ——那正是我們要的行為（不是每個事件都值得通知使用者）
        switch (eventType) {
            case OrderPaidEvent.TYPE -> dispatchUseCase.onOrderPaid(
                    objectMapper.readValue(payload, OrderPaidEvent.class));
            case OrderShippedEvent.TYPE -> dispatchUseCase.onOrderShipped(
                    objectMapper.readValue(payload, OrderShippedEvent.class));
            case OrderCompletedEvent.TYPE -> dispatchUseCase.onOrderCompleted(
                    objectMapper.readValue(payload, OrderCompletedEvent.class));
            case OrderCancelledEvent.TYPE -> dispatchUseCase.onOrderCancelled(
                    objectMapper.readValue(payload, OrderCancelledEvent.class));
            case RefundRequestedEvent.TYPE -> dispatchUseCase.onRefundRequested(
                    objectMapper.readValue(payload, RefundRequestedEvent.class));
            default -> {
                // 不通知的事件型別直接 ack，不留日誌——
                // order.created 在尖峰時每秒上萬筆，記一行就是一場日誌洪水
            }
        }
        log.debug("已處理事件 eventType={}", eventType);
    }
}
