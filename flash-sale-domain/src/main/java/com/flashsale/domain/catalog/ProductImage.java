package com.flashsale.domain.catalog;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.Set;

/**
 * 一張商品圖片（ADR-0027）。
 *
 * <p>存的是<b>物件鍵</b>而不是完整 URL：換 CDN 網域、換儲存端點
 * 都不該需要改資料。完整 URL 由應用層用設定裡的 base 組出來。
 *
 * @param sortOrder      0 為主圖。列表只顯示主圖，商品頁顯示全部
 * @param variantsReady  尺寸變體是否已產生。縮圖走慢車道，
 *                       所以剛掛上的圖會是 false；WebP 與過小的圖永遠是 false
 */
public record ProductImage(Long id, Long productId, String objectKey,
                           String contentType, long byteSize, int sortOrder,
                           boolean variantsReady) {

    /**
     * 允許的格式。
     *
     * <p><b>白名單而不是黑名單。</b> 黑名單擋不住沒想到的格式，
     * 而「沒想到的格式」正是 SVG 這種可以夾帶 script 的東西——
     * 圖片是直接從我們的網域提供的，一個帶 script 的 SVG
     * 就是一個同源的 XSS。
     */
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    /** 單張上限。太大的圖對使用者是慢，對我們是流量費。 */
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    public ProductImage {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "物件鍵不可為空");
        }
        if (byteSize <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "檔案大小必須大於 0");
        }
    }

    /**
     * 取這個用途下最合適的物件鍵。
     *
     * <p>變體還沒產生（或永遠不會產生）時退回原圖——
     * <b>由後端決定，不讓前端猜</b>：前端猜的話得知道變體的命名規則，
     * 而那是後端的實作細節，改了就會全站破圖。
     */
    public String keyFor(ImageVariant variant) {
        return variantsReady ? variant.keyOf(objectKey) : objectKey;
    }

    public static void requireSupported(String contentType, long byteSize) {
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA,
                    "只接受 JPEG / PNG / WebP");
        }
        if (byteSize <= 0 || byteSize > MAX_BYTES) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA,
                    "檔案大小需介於 1 byte 與 5 MB 之間");
        }
    }

    /**
     * 由內容雜湊組出物件鍵。
     *
     * <p>副檔名由 content type 決定而不是取自使用者傳來的檔名——
     * 檔名是使用者可控的字串，而它會變成物件鍵的一部分。
     */
    public static String objectKeyOf(String sha256, String contentType) {
        return sha256 + "." + extensionOf(contentType);
    }

    private static String extensionOf(String contentType) {
        return switch (contentType.toLowerCase()) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }
}
