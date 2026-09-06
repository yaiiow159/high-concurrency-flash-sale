package com.flashsale.domain.catalog;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.Set;

/** 一張商品圖片（ADR-0027）。 */
public record ProductImage(Long id, Long productId, String objectKey,
                           String contentType, long byteSize, int sortOrder,
                           boolean variantsReady) {

    /** 允許的格式。 */
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

    /** 取這個用途下最合適的物件鍵。 */
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

    /** 由內容雜湊組出物件鍵。 */
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
