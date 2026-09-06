package com.flashsale.domain.catalog.event;

import com.flashsale.domain.shared.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** 商品掛上了一張圖，下游據此產生尺寸變體（ADR-0027 決策 4）。 */
public record ProductImageAttachedEvent(
        String eventId,
        Long productId,
        String objectKey,
        String contentType,
        Instant occurredAt
) implements DomainEvent {

    public static final String TYPE = "product.image-attached";

    public static ProductImageAttachedEvent of(Long productId, String objectKey,
                                               String contentType, Instant now) {
        return new ProductImageAttachedEvent(UUID.randomUUID().toString(),
                productId, objectKey, contentType, now);
    }

    @Override
    public String eventType() {
        return TYPE;
    }

    /** 用物件鍵而不是商品 ID：變體是<b>物件</b>的屬性，與掛在哪個商品無關。 */
    @Override
    public String aggregateId() {
        return objectKey;
    }
}
