package com.flashsale.application.port.in.dto;

import com.flashsale.domain.catalog.ProductImage;

/**
 * 商品圖片。
 *
 * @param url 完整網址，由設定裡的 base 加物件鍵組成。
 *            <b>資料庫只存鍵</b>——換 CDN 網域不該需要改資料
 */
public record ProductImageView(Long imageId, String url, int sortOrder) {

    public static ProductImageView of(ProductImage image, String url) {
        return new ProductImageView(image.id(), url, image.sortOrder());
    }
}
