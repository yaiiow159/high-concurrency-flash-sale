package com.flashsale.application.port.in.dto;

/** 商品圖片。 */
public record ProductImageView(Long imageId, String url, String listUrl,
                               String thumbUrl, int sortOrder) {
}
