package com.flashsale.application.port.in;

import java.util.List;
import com.flashsale.application.port.in.dto.ProductSearchResult;
import com.flashsale.domain.catalog.event.ProductIndexChangedEvent;

/**
 * 商品搜尋與索引維護（ADR-0012）。
 */
public interface ProductSearchUseCase {

    ProductSearchResult search(String keyword, Long categoryId, String brand, int page, int size);

    /** 消費 {@code product.index-changed} 事件更新索引。 */
    void applyIndexChange(ProductIndexChangedEvent event);

    /** 整份重建索引，供維運使用。 */
    long reindex();
    /** 搜尋建議。 */
    List<String> suggest(String keyword);

}
