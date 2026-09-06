package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.domain.home.CarouselSlide;
import com.flashsale.domain.home.Visibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 新增／修改輪播圖。站內連結的檢查在 {@link CarouselSlide} 的建構子。 */
public record CarouselSlideRequest(
        @NotBlank(message = "請先上傳圖片") @Size(max = 128) String objectKey,
        @Size(max = 64) String title,
        @Size(max = 256) String linkUrl,
        int sortOrder,
        boolean enabled,
        Instant visibleFrom,
        Instant visibleTo
) {

    public CarouselSlide toDomain(Long id) {
        return new CarouselSlide(id, objectKey, title, linkUrl, sortOrder,
                new Visibility(enabled, visibleFrom, visibleTo));
    }

    /** @param imageUrl 後台要看得到縮圖才知道自己在改哪一張 */
    public record View(Long slideId, String objectKey, String imageUrl, String title,
                       String linkUrl, int sortOrder, boolean enabled,
                       Instant visibleFrom, Instant visibleTo) {

        public static View from(CarouselSlide slide, String imageUrl) {
            return new View(slide.id(), slide.objectKey(), imageUrl, slide.title(),
                    slide.linkUrl(), slide.sortOrder(), slide.visibility().enabled(),
                    slide.visibility().from(), slide.visibility().to());
        }
    }
}
