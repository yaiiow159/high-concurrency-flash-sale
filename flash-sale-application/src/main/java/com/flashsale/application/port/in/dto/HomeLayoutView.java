package com.flashsale.application.port.in.dto;

import java.util.List;

/**
 * 首頁的完整內容，一次給完。
 *
 * <p>拆成多支端點的話首頁要打五六次請求，而首頁是整站流量最大的一頁。
 */
public record HomeLayoutView(List<SectionView> sections) {

    /**
     * @param slides   只有輪播版位有值
     * @param products 只有商品版位有值
     */
    public record SectionView(
            Long sectionId,
            String type,
            String title,
            String subtitle,
            List<SlideView> slides,
            List<ProductView> products
    ) {
    }

    public record SlideView(Long slideId, String imageUrl, String title, String linkUrl) {
    }
}
