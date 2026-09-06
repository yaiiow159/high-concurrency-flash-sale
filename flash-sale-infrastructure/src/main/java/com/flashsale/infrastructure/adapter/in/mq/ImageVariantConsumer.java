package com.flashsale.infrastructure.adapter.in.mq;

import com.flashsale.application.port.in.ImageVariantUseCase;
import com.flashsale.domain.catalog.event.ProductImageAttachedEvent;
import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** 產生圖片變體（ADR-0027 決策 4）。 */
@Component
public class ImageVariantConsumer {

    private static final Logger log = LoggerFactory.getLogger(ImageVariantConsumer.class);

    private final ImageVariantUseCase imageVariantUseCase;
    private final DomainEventRouter router;

    public ImageVariantConsumer(ImageVariantUseCase imageVariantUseCase,
                                DomainEventRouter router) {
        this.imageVariantUseCase = imageVariantUseCase;
        this.router = router;
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_EVENT,
            groupId = "${flash-sale.mq.image-variant-group:catalog-image-variants}",
            concurrency = "${flash-sale.mq.image-variant-concurrency:1}")
    public void onDomainEvent(@Payload String payload,
                              @Header(name = KafkaTopics.HEADER_EVENT_TYPE, required = false)
                              String eventType) throws Exception {
        router.route(payload, eventType, ProductImageAttachedEvent.TYPE,
                ProductImageAttachedEvent.class, event -> {
                    imageVariantUseCase.generateVariants(event);
                    log.debug("已處理圖片變體 key={}", event.objectKey());
                });
    }
}
