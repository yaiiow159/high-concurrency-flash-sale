package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ProductSearchResult;
import com.flashsale.domain.catalog.event.ProductIndexChangedEvent;

/**
 * 商品搜尋與索引維護（ADR-0012）。
 */
public interface ProductSearchUseCase {

    ProductSearchResult search(String keyword, Long categoryId, String brand, int page, int size);

    /**
     * 消費 {@code product.index-changed} 事件更新索引。
     *
     * <p><b>冪等是必答題</b>：Outbox 是至少一次語意。
     * 這裡天然冪等——以商品 ID 為文件 ID 的寫入是覆寫而非新增，
     * 重複投遞只是再寫一次同樣的內容。
     */
    void applyIndexChange(ProductIndexChangedEvent event);

    /** 整份重建索引，供維運使用。 */
    long reindex();
}
