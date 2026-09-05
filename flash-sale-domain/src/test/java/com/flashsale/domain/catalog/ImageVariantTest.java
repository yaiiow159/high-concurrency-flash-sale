package com.flashsale.domain.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 圖片變體（ADR-0027 決策 4）。
 *
 * <p>變體鍵算錯的症狀是<b>全站破圖</b>，而它不會拋任何例外——
 * 只會有一堆 404。
 */
@DisplayName("圖片變體")
class ImageVariantTest {

    private static final String KEY =
            "a05132742ed4715142593981a29b1851db1df8ee92abbecf68e2c56da8cf4e31.png";

    @Nested
    @DisplayName("命名")
    class Naming {

        @Test
        @DisplayName("後綴加在副檔名之前，而不是接在最後面")
        void suffixGoesBeforeExtension() {
            // 接在最後面（xxx.png_thumb）會讓 CDN 與瀏覽器認不出這是圖片，
            // 而那不會報錯，只會讓某些客戶端拒絕顯示
            assertThat(ImageVariant.THUMB.keyOf(KEY))
                    .endsWith("_thumb.png")
                    .startsWith("a05132742ed4");
        }

        @Test
        @DisplayName("同一張原圖永遠得到同一個變體鍵")
        void deterministic() {
            // 變體鍵是推導出來的，不另外存——推導不穩定的話
            // 每次部署都會產生一批新的孤兒
            assertThat(ImageVariant.LIST.keyOf(KEY)).isEqualTo(ImageVariant.LIST.keyOf(KEY));
        }

        @Test
        @DisplayName("三種尺寸的鍵互不相同")
        void variantsDoNotCollide() {
            assertThat(ImageVariant.THUMB.keyOf(KEY))
                    .isNotEqualTo(ImageVariant.LIST.keyOf(KEY))
                    .isNotEqualTo(ImageVariant.DETAIL.keyOf(KEY));
        }

        @Test
        @DisplayName("沒有副檔名的鍵也不能組出壞掉的名字")
        void handlesKeyWithoutExtension() {
            assertThat(ImageVariant.THUMB.keyOf("abc123")).isEqualTo("abc123_thumb");
        }

        @Test
        @DisplayName("只切最後一個點——雜湊裡不會有點，但別的命名可能會")
        void splitsOnLastDot() {
            assertThat(ImageVariant.THUMB.keyOf("a.b.png")).isEqualTo("a.b_thumb.png");
        }
    }

    @Nested
    @DisplayName("尺寸由小到大")
    class Sizes {

        @Test
        @DisplayName("縮圖 < 列表 < 詳情")
        void ordered() {
            // 順序反了的話，列表會載進比詳情頁還大的圖——
            // 而列表一次載十幾張，那是流量的大宗
            assertThat(ImageVariant.THUMB.maxEdge()).isLessThan(ImageVariant.LIST.maxEdge());
            assertThat(ImageVariant.LIST.maxEdge()).isLessThan(ImageVariant.DETAIL.maxEdge());
        }
    }

    @Nested
    @DisplayName("挑用哪一個")
    class Selection {

        private static ProductImage image(boolean variantsReady) {
            return new ProductImage(1L, 1L, KEY, "image/png", 1024, 0, variantsReady);
        }

        @Test
        @DisplayName("變體已產生時用變體")
        void usesVariantWhenReady() {
            assertThat(image(true).keyFor(ImageVariant.LIST)).endsWith("_list.png");
        }

        @Test
        @DisplayName("變體還沒好時退回原圖，而不是給一個不存在的網址")
        void fallsBackToOriginal() {
            // 縮圖走慢車道，掛上圖到變體產生之間有一段空窗。
            // 這段期間給變體網址就是破圖
            assertThat(image(false).keyFor(ImageVariant.LIST)).isEqualTo(KEY);
        }
    }
}
