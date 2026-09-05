package com.flashsale.infrastructure.adapter.out.media;

import com.flashsale.domain.catalog.ImageVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 縮圖產生（ADR-0027 決策 4）。
 *
 * <p>不需要 Docker：進去是位元組、出來是位元組，中間全在記憶體裡。
 */
@DisplayName("縮圖產生")
class ImageIoVariantGeneratorTest {

    private final ImageIoVariantGenerator generator = new ImageIoVariantGenerator();

    private static byte[] image(int width, int height, String format) throws Exception {
        BufferedImage source = new BufferedImage(width, height,
                "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = source.createGraphics();
        graphics.setColor(new Color(46, 130, 150));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, format, out);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Nested
    @DisplayName("尺寸")
    class Sizing {

        @Test
        @DisplayName("長邊縮到目標，短邊等比跟著縮")
        void scalesProportionally() throws Exception {
            byte[] variant = generator
                    .generate(image(1600, 800, "png"), "image/png", ImageVariant.THUMB)
                    .orElseThrow();

            BufferedImage result = decode(variant);
            // 不等比的話商品圖會被拉扁，而那是每一張列表圖都看得到的
            assertThat(result.getWidth()).isEqualTo(ImageVariant.THUMB.maxEdge());
            assertThat(result.getHeight()).isEqualTo(ImageVariant.THUMB.maxEdge() / 2);
        }

        @Test
        @DisplayName("直式圖以高度為長邊")
        void handlesPortrait() throws Exception {
            BufferedImage result = decode(generator
                    .generate(image(800, 1600, "png"), "image/png", ImageVariant.THUMB)
                    .orElseThrow());

            assertThat(result.getHeight()).isEqualTo(ImageVariant.THUMB.maxEdge());
        }

        @Test
        @DisplayName("原圖比目標小就不產——放大只會得到模糊又更大的檔案")
        void doesNotUpscale() throws Exception {
            assertThat(generator.generate(image(64, 64, "png"), "image/png", ImageVariant.DETAIL))
                    .isEmpty();
        }

        @Test
        @DisplayName("剛好等於目標也不產")
        void doesNotRegenerateAtExactSize() throws Exception {
            int edge = ImageVariant.THUMB.maxEdge();
            assertThat(generator.generate(image(edge, edge, "png"), "image/png", ImageVariant.THUMB))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("格式")
    class Formats {

        @Test
        @DisplayName("PNG 縮完仍保有 alpha 通道")
        void preservesAlphaForPng() throws Exception {
            BufferedImage result = decode(generator
                    .generate(image(1600, 1600, "png"), "image/png", ImageVariant.LIST)
                    .orElseThrow());

            // 一律用 RGB 的話，透明背景的去背商品圖會變成黑底
            assertThat(result.getColorModel().hasAlpha()).isTrue();
        }

        @Test
        @DisplayName("JPEG 走不帶 alpha 的路徑")
        void jpegStaysOpaque() throws Exception {
            Optional<byte[]> variant = generator
                    .generate(image(1600, 1600, "jpg"), "image/jpeg", ImageVariant.LIST);

            assertThat(variant).isPresent();
            assertThat(decode(variant.get()).getWidth()).isEqualTo(ImageVariant.LIST.maxEdge());
        }

        @Test
        @DisplayName("解不開的格式回 empty，不往外拋")
        void unsupportedFormatYieldsEmpty() {
            // WebP 會走到這裡：ImageIO 不支援解碼。
            // 往外拋的話這則訊息會一直重試，而重試不會讓格式突然被支援
            assertThat(generator.generate("not an image".getBytes(), "image/webp",
                    ImageVariant.THUMB)).isEmpty();
        }

        @Test
        @DisplayName("壞掉的位元組回 empty，不往外拋")
        void corruptBytesYieldEmpty() {
            assertThat(generator.generate(new byte[]{1, 2, 3}, "image/png", ImageVariant.THUMB))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("大小")
    class ByteSize {

        @Test
        @DisplayName("縮圖比列表小，列表比詳情小")
        void smallerVariantsAreSmallerFiles() throws Exception {
            byte[] original = image(1600, 1600, "jpg");

            int thumb = generator.generate(original, "image/jpeg", ImageVariant.THUMB)
                    .orElseThrow().length;
            int list = generator.generate(original, "image/jpeg", ImageVariant.LIST)
                    .orElseThrow().length;
            int detail = generator.generate(original, "image/jpeg", ImageVariant.DETAIL)
                    .orElseThrow().length;

            // 縮圖存在的唯一理由就是省流量。順序反了代表某個環節搞錯尺寸，
            // 而畫面上完全看不出來——只有帳單看得出來
            assertThat(thumb).isLessThan(list);
            assertThat(list).isLessThan(detail);
        }
    }
}
