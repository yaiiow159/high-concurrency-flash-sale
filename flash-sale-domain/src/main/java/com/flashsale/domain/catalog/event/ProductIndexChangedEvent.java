package com.flashsale.domain.catalog.event;

import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.shared.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * 商品的可搜尋狀態有變動（ADR-0012）。
 *
 * <h2>事件刻意很薄：只帶 ID，不帶商品內容</h2>
 *
 * <p>直覺的做法是把整份可搜尋文件塞進事件，消費端拿了就寫進索引，
 * 省下一次讀取。這裡不那樣做，理由是<b>索引的形狀會獨立於領域事件演化</b>：
 * 之後想加一個分面（例如顏色、材質），若事件自帶內容，
 * 就得改事件結構，而佇列裡與 outbox 表裡還躺著舊格式的事件。
 *
 * <p>只帶 ID 的話，消費端每次都從 Catalog 讀當下的狀態——
 * 加欄位只要改消費端與索引 mapping，事件完全不動。
 *
 * <p>代價是每則事件多一次資料庫讀取。商品變更是低頻操作
 * （一天幾十次，不是每秒幾萬次），這個代價可以忽略。
 *
 * <h2>上架與下架共用同一個事件</h2>
 *
 * <p>因為消費端要做的判斷完全一樣：讀當下狀態，
 * 上架就寫進索引、非上架就從索引移除。拆成兩個事件會讓消費端
 * 多一個分支，而那個分支的兩邊會做同一件事。
 */
public record ProductIndexChangedEvent(
        String eventId,
        Long productId,
        Instant occurredAt
) implements DomainEvent {

    public static final String TYPE = "product.index-changed";

    public static ProductIndexChangedEvent of(Product product, Instant now) {
        return new ProductIndexChangedEvent(UUID.randomUUID().toString(), product.id(), now);
    }

    @Override
    public String eventType() {
        return TYPE;
    }

    /**
     * 以商品 ID 作為 partition key。
     *
     * <p>同一個商品的多次變更必須有序：先下架再上架若順序顛倒，
     * 索引會停在「已移除」而商品其實是上架的——而且不會有任何東西發現，
     * 直到有人抱怨搜不到。
     */
    @Override
    public String aggregateId() {
        return String.valueOf(productId);
    }
}
