package com.flashsale.application.port.in;

import com.flashsale.domain.catalog.event.ProductImageAttachedEvent;

/** 產生圖片的尺寸變體（ADR-0027 決策 4）。 */
public interface ImageVariantUseCase {

    /** 為一個物件產生所有尺寸。 */
    void generateVariants(ProductImageAttachedEvent event);
}
