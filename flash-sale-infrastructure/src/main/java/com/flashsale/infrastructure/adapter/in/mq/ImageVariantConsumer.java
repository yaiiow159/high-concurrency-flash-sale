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

/**
 * 產生圖片變體（ADR-0027 決策 4）。
 *
 * <h2>「把歷史全部重跑一次會怎樣」</h2>
 *
 * <p>CLAUDE.md 鐵則 4 的第二題在這裡的答案是<b>沒事</b>：
 * 變體的鍵由原圖的內容雜湊推導，重放只是把同樣的位元組寫到同樣的鍵。
 * 而服務裡還會先檢查存在與否，所以重放的實際成本是一輪 HEAD 請求。
 *
 * <p>這與銷量那個消費端形成對照——那裡的增量 UPDATE 重放一次就是翻倍，
 * 必須另外用一張表擋。<b>冪等不是一種寫法，是要逐個消費端回答的問題。</b>
 *
 * <h2>concurrency 只有 1</h2>
 *
 * <p>縮圖是 CPU 密集的，而這個行程還跑著秒殺的熱路徑。
 * 開多執行緒解碼大圖會把 CPU 吃掉，而那正是尖峰時最不能讓的東西。
 * 慢一點沒關係——商品上架不是即時需求。
 */
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
