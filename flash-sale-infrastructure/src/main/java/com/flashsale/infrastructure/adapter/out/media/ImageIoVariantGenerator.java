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

/** 用 JDK 內建的 ImageIO 產生尺寸變體。 */
@Component
public class ImageIoVariantGenerator implements ImageVariantGenerator {

    private static final Logger log = LoggerFactory.getLogger(ImageIoVariantGenerator.class);

    /** 產生一個尺寸。 */
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
