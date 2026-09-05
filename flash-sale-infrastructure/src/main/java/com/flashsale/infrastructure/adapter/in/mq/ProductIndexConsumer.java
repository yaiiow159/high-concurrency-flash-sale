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

/**
 * 把商品變動同步到搜尋索引（ADR-0012）。
 *
 * <p><b>冪等是必答題</b>，而這裡天然成立：文件 ID 就是商品 ID，
 * 寫入是覆寫而非新增，重複投遞只是再寫一次同樣的內容。
 *
 * <p>併發設 1。同一個商品的事件靠 partition key（商品 ID）已經有序，
 * 但商品變更是一天幾十次的低頻操作，多開執行緒只是多佔連線——
 * 而連線是這個系統目前比較稀缺的東西。
 */
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
            concurrency = "1")
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
