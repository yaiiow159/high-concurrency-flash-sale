package com.flashsale.domain.catalog.event;

import com.flashsale.domain.shared.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * 商品掛上了一張圖，下游據此產生尺寸變體（ADR-0027 決策 4）。
 *
 * <p>縮圖在<b>慢車道</b>做：原圖先可用，變體晚幾秒到——
 * 那幾秒商品多半還沒上架，沒有人看得到。
 * 放在請求路徑上做的話，上傳一張圖要等縮圖跑完才回應。
 *
 * @param objectKey 原圖的物件鍵。變體的鍵由它推導，不另外存
 */
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
