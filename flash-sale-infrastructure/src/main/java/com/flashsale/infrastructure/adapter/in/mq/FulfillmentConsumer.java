package com.flashsale.infrastructure.adapter.in.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.application.port.in.FulfillmentUseCase;
import com.flashsale.domain.order.event.OrderPaidEvent;
import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * 付款完成後建立出貨單。
 *
 * <p><b>冪等是必答題。</b>Outbox 是至少一次語意，同一個付款事件一定會被重複投遞，
 * 而重複建立的後果是同一張訂單出兩次貨。冪等由
 * {@code ShipmentRepository.saveIfAbsent} 與 {@code order_no} 的唯一索引共同保證。
 *
 * <p>與 {@code SeckillCompensationConsumer} 共用同一個 topic 但不同 group，
 * 因此兩邊各自收到完整的事件流，互不影響——
 * 若共用 group，一則事件只會被其中一個消費掉。
 */
@Component
public class FulfillmentConsumer {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentConsumer.class);

    private final FulfillmentUseCase fulfillmentUseCase;
    private final ObjectMapper objectMapper;

    public FulfillmentConsumer(FulfillmentUseCase fulfillmentUseCase, ObjectMapper objectMapper) {
        this.fulfillmentUseCase = fulfillmentUseCase;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_EVENT,
            groupId = "${flash-sale.mq.fulfillment-group:fulfillment-shipment-creator}",
            concurrency = "${flash-sale.mq.fulfillment-concurrency:2}")
    public void onDomainEvent(@Payload String payload,
                              @Header(name = KafkaTopics.HEADER_EVENT_TYPE, required = false)
                              String eventType) throws Exception {
        if (!OrderPaidEvent.TYPE.equals(eventType)) {
            // 同一個 topic 承載多種事件，非本消費組關心的直接 ack
            return;
        }
        OrderPaidEvent event = objectMapper.readValue(payload, OrderPaidEvent.class);
        fulfillmentUseCase.prepareShipment(event);
        log.debug("已處理付款事件 orderNo={}", event.orderNo());
    }
}
