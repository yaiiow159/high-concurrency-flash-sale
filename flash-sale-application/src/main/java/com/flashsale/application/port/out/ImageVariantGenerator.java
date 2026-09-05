package com.flashsale.application.port.out;

import com.flashsale.domain.catalog.ImageVariant;

import java.util.Optional;

/**
 * 產生圖片的尺寸變體（出站埠，ADR-0027 決策 4）。
 *
 * <p>做成埠而不是直接在服務裡呼叫 ImageIO：影像處理是一個
 * <b>會被換掉</b>的實作細節（品質不夠好就換 Thumbnailator、
 * 量大了就丟給外部服務），而應用層不該因此改動。
 */
public interface ImageVariantGenerator {

    /**
     * @return 產不出來時回 {@code empty}——不支援的格式（WebP）、
     *         或原圖比目標還小（放大只會得到模糊的大圖，而且檔案更大）。
     *         <b>不是錯誤</b>：呼叫端會把那張圖標記成沒有變體，前端退回原圖
     */
    Optional<byte[]> generate(byte[] original, String contentType, ImageVariant variant);
}
