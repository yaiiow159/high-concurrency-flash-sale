package com.flashsale.domain.catalog.event;

import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.shared.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** 商品的可搜尋狀態有變動（ADR-0012）。 */
public record ProductIndexChangedEvent(
        String eventId,
        Long productId,
        Instant occurredAt
) implements DomainEvent {

    public static final String TYPE = "product.index-changed";

    public static ProductIndexChangedEvent of(Product product, Instant now) {
        return of(product.id(), now);
    }

    /** 評分或庫存變了：手上只有商品 ID，沒有聚合根。 */
    public static ProductIndexChangedEvent of(Long productId, Instant now) {
        return new ProductIndexChangedEvent(UUID.randomUUID().toString(), productId, now);
    }

    @Override
    public String eventType() {
        return TYPE;
    }

    /** 以商品 ID 作為 partition key。 */
    @Override
    public String aggregateId() {
        return String.valueOf(productId);
    }
}
