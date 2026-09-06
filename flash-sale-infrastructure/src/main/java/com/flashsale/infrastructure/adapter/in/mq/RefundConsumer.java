package com.flashsale.infrastructure.adapter.in.mq;

import com.flashsale.application.port.in.RefundExecutionUseCase;
import com.flashsale.domain.aftersales.event.RefundRequestedEvent;
import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** 執行已核可的退款——退款 Saga 的慢車道（ADR-0011）。 */
@Component
public class RefundConsumer {

    private static final Logger log = LoggerFactory.getLogger(RefundConsumer.class);

    private final RefundExecutionUseCase refundExecutionUseCase;
    private final DomainEventRouter router;

    public RefundConsumer(RefundExecutionUseCase refundExecutionUseCase,
                          DomainEventRouter router) {
        this.refundExecutionUseCase = refundExecutionUseCase;
        this.router = router;
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_EVENT,
            groupId = "${flash-sale.mq.refund-group:aftersales-refund-executor}",
            concurrency = "${flash-sale.mq.refund-concurrency:1}")
    public void onDomainEvent(@Payload String payload,
                              @Header(name = KafkaTopics.HEADER_EVENT_TYPE, required = false)
                              String eventType) throws Exception {
        router.route(payload, eventType, RefundRequestedEvent.TYPE,
                RefundRequestedEvent.class, event -> {
                    refundExecutionUseCase.execute(event);
                    log.debug("已處理退款事件 returnNo={}", event.returnNo());
                });
    }
}
