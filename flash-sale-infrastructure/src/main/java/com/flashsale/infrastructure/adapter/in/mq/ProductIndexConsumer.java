package com.flashsale.infrastructure.adapter.in.mq;

import com.flashsale.application.port.in.ProductSearchUseCase;
import com.flashsale.domain.catalog.event.ProductIndexChangedEvent;
import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** 把商品變動同步到搜尋索引（ADR-0012）。 */
@Component
public class ProductIndexConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProductIndexConsumer.class);

    private final ProductSearchUseCase productSearchUseCase;
    private final DomainEventRouter router;

    public ProductIndexConsumer(ProductSearchUseCase productSearchUseCase,
                                DomainEventRouter router) {
        this.productSearchUseCase = productSearchUseCase;
        this.router = router;
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_EVENT,
            groupId = "${flash-sale.mq.search-index-group:catalog-search-indexer}",
            concurrency = "1",
            // 進了死信只能靠人工重建，因此用慢檔的重試預算
            containerFactory = "resilientKafkaListenerContainerFactory")
    public void onDomainEvent(@Payload String payload,
                              @Header(name = KafkaTopics.HEADER_EVENT_TYPE, required = false)
                              String eventType) throws Exception {
        router.route(payload, eventType, ProductIndexChangedEvent.TYPE,
                ProductIndexChangedEvent.class, event -> {
                    productSearchUseCase.applyIndexChange(event);
                    log.debug("已同步商品 {} 到搜尋索引", event.productId());
                });
    }
}
