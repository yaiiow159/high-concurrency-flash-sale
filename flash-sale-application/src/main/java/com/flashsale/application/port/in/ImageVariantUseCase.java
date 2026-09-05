package com.flashsale.application.port.in;

import com.flashsale.domain.catalog.event.ProductImageAttachedEvent;

/** 產生圖片的尺寸變體（ADR-0027 決策 4）。 */
public interface ImageVariantUseCase {

    /**
     * 為一個物件產生所有尺寸。
     *
     * <p><b>冪等</b>：變體的鍵由原圖的內容雜湊推導，同樣的輸入永遠寫到
     * 同樣的鍵。消費端重放整個 topic 只是重寫同樣的位元組，不會出錯——
     * 但仍然會先檢查是否已存在，避免白做工。
     */
    void generateVariants(ProductImageAttachedEvent event);
}
