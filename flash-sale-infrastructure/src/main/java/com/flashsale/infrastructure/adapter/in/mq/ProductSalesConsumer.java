package com.flashsale.infrastructure.adapter.in.mq;

import com.flashsale.application.port.in.ProductSalesUseCase;
import com.flashsale.domain.order.event.OrderPaidEvent;
import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** 銷量計入。 */
@Component
public class ProductSalesConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProductSalesConsumer.class);

    private final ProductSalesUseCase productSalesUseCase;
    private final DomainEventRouter router;

    public ProductSalesConsumer(ProductSalesUseCase productSalesUseCase,
                                DomainEventRouter router) {
        this.productSalesUseCase = productSalesUseCase;
        this.router = router;
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_EVENT,
            groupId = "${flash-sale.mq.product-sales-group:product-sales}",
            concurrency = "${flash-sale.mq.product-sales-concurrency:2}")
    public void onDomainEvent(@Payload String payload,
                              @Header(name = KafkaTopics.HEADER_EVENT_TYPE, required = false)
                              String eventType) throws Exception {
        router.route(payload, eventType, OrderPaidEvent.TYPE, OrderPaidEvent.class, event -> {
            if (productSalesUseCase.recordSale(event.orderNo(), event.userId())) {
                log.debug("訂單 {} 已計入銷量", event.orderNo());
            }
        });
    }
}
