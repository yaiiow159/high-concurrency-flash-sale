package com.flashsale.infrastructure.adapter.in.mq;

import com.flashsale.application.port.in.FulfillmentUseCase;
import com.flashsale.domain.order.event.OrderPaidEvent;
import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** 付款完成後建立出貨單。 */
@Component
public class FulfillmentConsumer {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentConsumer.class);

    private final FulfillmentUseCase fulfillmentUseCase;
    private final DomainEventRouter router;

    public FulfillmentConsumer(FulfillmentUseCase fulfillmentUseCase, DomainEventRouter router) {
        this.fulfillmentUseCase = fulfillmentUseCase;
        this.router = router;
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_EVENT,
            groupId = "${flash-sale.mq.fulfillment-group:fulfillment-shipment-creator}",
            concurrency = "${flash-sale.mq.fulfillment-concurrency:2}")
    public void onDomainEvent(@Payload String payload,
                              @Header(name = KafkaTopics.HEADER_EVENT_TYPE, required = false)
                              String eventType) throws Exception {
        // 同一個 topic 承載多種事件；型別不符時 router 直接略過，呼叫端正常 ack
        router.route(payload, eventType, OrderPaidEvent.TYPE, OrderPaidEvent.class, event -> {
            fulfillmentUseCase.prepareShipment(event);
            log.debug("已處理付款事件 orderNo={}", event.orderNo());
        });
    }
}
