package com.flashsale.domain.home;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;

/**
 * 輪播圖的一張。
 *
 * @param objectKey 圖片的物件鍵，走 ADR-0027 那條上傳路徑；不存完整 URL
 * @param linkUrl   點下去要去哪。只收站內相對路徑
 */
public record CarouselSlide(
        Long id,
        String objectKey,
        String title,
        String linkUrl,
        int sortOrder,
        Visibility visibility
) {

    public CarouselSlide {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "輪播圖必須有圖片");
        }
        requireInternalLink(linkUrl);
    }

    /**
     * 只收站內相對路徑。
     *
     * <p>放行外部網址等於讓能編輯輪播圖的人在首頁掛任意連結——
     * 那是釣魚頁最想要的位置。要導去站外應該是另一個明確的功能，不是這裡的預設能力。
     */
    private static void requireInternalLink(String linkUrl) {
        if (linkUrl == null || linkUrl.isBlank()) {
            return;
        }
        if (!linkUrl.startsWith("/") || linkUrl.startsWith("//")) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "輪播圖連結只能是站內路徑（以 / 開頭）");
        }
    }

    public boolean isVisibleAt(Instant now) {
        return visibility.isVisibleAt(now);
    }
}
