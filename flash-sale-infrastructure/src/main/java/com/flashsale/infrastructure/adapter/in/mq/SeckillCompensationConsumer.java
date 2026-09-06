package com.flashsale.infrastructure.adapter.in.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.application.port.in.StockCompensationUseCase;
import com.flashsale.application.port.out.message.SeckillOrderMessage;
import com.flashsale.domain.order.event.OrderCancelledEvent;
import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** 庫存補償消費端——Saga 補償鏈的入口。 */
@Component
public class SeckillCompensationConsumer {

    private static final Logger log = LoggerFactory.getLogger(SeckillCompensationConsumer.class);

    private final StockCompensationUseCase compensationUseCase;
    private final DomainEventRouter router;
    /** 死信沒有事件型別可以分派，仍然要自己反序列化。 */
    private final ObjectMapper objectMapper;

    public SeckillCompensationConsumer(StockCompensationUseCase compensationUseCase, DomainEventRouter router,
                                       ObjectMapper objectMapper) {
        this.compensationUseCase = compensationUseCase;
        this.router = router;
        this.objectMapper = objectMapper;
    }

    /** 領域事件 topic：只處理訂單關閉，其餘型別直接略過。 */
    @KafkaListener(
            topics = KafkaTopics.ORDER_EVENT,
            groupId = "${flash-sale.mq.compensation-group:seckill-stock-compensator}",
            concurrency = "${flash-sale.mq.compensation-concurrency:3}")
    public void onDomainEvent(@Payload String payload,
                              @Header(name = KafkaTopics.HEADER_EVENT_TYPE, required = false) String eventType)
            throws Exception {
        router.route(payload, eventType, OrderCancelledEvent.TYPE,
                OrderCancelledEvent.class, compensationUseCase::compensate);
    }

    /** 建單死信：訂單未建立，只能依訊息本身退庫。 */
    @KafkaListener(
            topics = KafkaTopics.ORDER_CREATE_DLT,
            groupId = "${flash-sale.mq.dlt-compensation-group:seckill-dlt-compensator}")
    public void onOrderCreateDeadLetter(
            @Payload String payload,
            @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) String failureReason)
            throws Exception {
        SeckillOrderMessage message = objectMapper.readValue(payload, SeckillOrderMessage.class);
        log.warn("開始處理死信退庫 orderNo={}, 原因={}", message.orderNo(), failureReason);
        compensationUseCase.compensateDeadLetter(message,
                failureReason == null ? "建單重試耗盡" : failureReason);
    }
}
