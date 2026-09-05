package com.flashsale.infrastructure.adapter.out.media;

import com.flashsale.application.port.out.ImageVariantGenerator;
import com.flashsale.domain.catalog.ImageVariant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

/**
 * 用 JDK 內建的 ImageIO 產生尺寸變體。
 *
 * <h2>為什麼不加影像處理函式庫</h2>
 *
 * <p>ImageIO 是 JDK 的一部分，處理 JPEG 與 PNG 綽綽有餘，
 * 而這裡要做的只是等比縮小。引入 Thumbnailator 或 ImageMagick
 * 換來的是更好的品質與更多格式，代價是一個新的相依
 * （ImageMagick 甚至是一個系統層的二進位）。
 * 真的需要那些能力時再換，那是一個獨立的決定。
 *
 * <h2>WebP 產不出變體，而這是刻意接受的</h2>
 *
 * <p><b>JDK 的 ImageIO 不支援 WebP 解碼。</b> 上傳白名單仍然收 WebP——
 * 瀏覽器支援得很好，而原圖本來就能直接用。
 * 產不出變體時回 empty，呼叫端會把那張圖標記成「沒有變體」，
 * 前端就退回原圖。
 *
 * <p>這比「不准上傳 WebP」好：後者為了一個內部限制去限縮使用者，
 * 而那個限制是可以被無感吸收的。
 */
@Component
public class ImageIoVariantGenerator implements ImageVariantGenerator {

    private static final Logger log = LoggerFactory.getLogger(ImageIoVariantGenerator.class);

    /**
     * 產生一個尺寸。
     *
     * <p>原圖比目標還小時<b>不放大</b>——放大只會得到一張模糊的大圖，
     * 而且檔案比原圖還大。此時回 empty，讓呼叫端沿用原圖。
     */
    @Override
    public Optional<byte[]> generate(byte[] original, String contentType, ImageVariant variant) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(original));
            if (source == null) {
                // ImageIO 讀不懂就是不支援的格式（WebP 會走到這裡）
                log.info("無法解碼此格式，略過變體 contentType={}", contentType);
                return Optional.empty();
            }

            int longEdge = Math.max(source.getWidth(), source.getHeight());
            if (longEdge <= variant.maxEdge()) {
                return Optional.empty();
            }

            double scale = (double) variant.maxEdge() / longEdge;
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

            return Optional.of(encode(resize(source, width, height, contentType), contentType));
        } catch (Exception failure) {
            // 產不出變體不是致命的——原圖仍然能用。
            // 往外丟會讓這則訊息一直重試，而重試不會讓格式突然被支援
            log.warn("產生變體失敗，略過 variant={}", variant, failure);
            return Optional.empty();
        }
    }

    private static BufferedImage resize(BufferedImage source, int width, int height,
                                        String contentType) {
        // PNG 要保留透明度，JPEG 沒有 alpha 通道。
        // 一律用 ARGB 再輸出 JPEG 的話，透明區域會變成黑色
        int type = supportsAlpha(contentType)
                ? BufferedImage.TYPE_INT_ARGB
                : BufferedImage.TYPE_INT_RGB;

        BufferedImage target = new BufferedImage(width, height, type);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] encode(BufferedImage image, String contentType) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, formatOf(contentType), out);
        return out.toByteArray();
    }

    private static boolean supportsAlpha(String contentType) {
        return "image/png".equalsIgnoreCase(contentType);
    }

    private static String formatOf(String contentType) {
        return "image/png".equalsIgnoreCase(contentType) ? "png" : "jpg";
    }
}
