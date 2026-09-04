package com.flashsale.domain.catalog;

import com.flashsale.domain.shared.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * keyset 游標與排序（ADR-0021）。
 *
 * <p>這裡壞掉的方式都不會拋錯：游標少了小數位會讓同一元內的商品重複或跳號，
 * 而那要翻到第幾頁才看得出來。
 */
@DisplayName("商品游標與排序")
class ProductCursorTest {

    @Nested
    @DisplayName("編解碼")
    class Codec {

        @Test
        @DisplayName("只有 id 時編成純數字")
        void idOnly() {
            assertThat(ProductCursor.ofId(50004L).encode()).isEqualTo("50004");
            assertThat(ProductCursor.decode("50004"))
                    .isEqualTo(new ProductCursor(null, 50004L));
        }

        @Test
        @DisplayName("複合游標保留小數——截成整數會讓同一元內的商品全變同值")
        void keepsDecimals() {
            // 229.50 與 229.99 若都截成 229，翻頁時它們會互相蓋掉
            ProductCursor cursor = ProductCursor.of(new BigDecimal("229.50"), 12345L);

            assertThat(cursor.encode()).isEqualTo("229.50:12345");
            assertThat(ProductCursor.decode("229.50:12345").sortValue())
                    .isEqualByComparingTo("229.50");
        }

        @Test
        @DisplayName("編碼後再解碼要拿回同一個值")
        void roundTrip() {
            ProductCursor original = ProductCursor.of(new BigDecimal("1234.05"), 99L);

            ProductCursor decoded = ProductCursor.decode(original.encode());

            assertThat(decoded.id()).isEqualTo(99L);
            assertThat(decoded.sortValue()).isEqualByComparingTo("1234.05");
        }

        @Test
        @DisplayName("解不開就當第一頁，不報錯")
        void garbageIsFirstPage() {
            // 游標會出現在網址上，而使用者會改它、也會貼舊連結。
            // 逛商品列表不該因為網址被改壞而失敗
            assertThat(ProductCursor.decode(null)).isNull();
            assertThat(ProductCursor.decode("")).isNull();
            assertThat(ProductCursor.decode("abc")).isNull();
            assertThat(ProductCursor.decode("229.5:xyz")).isNull();
            assertThat(ProductCursor.decode(":::")).isNull();
        }
    }

    @Nested
    @DisplayName("排序方式")
    class Sorting {

        @Test
        @DisplayName("非唯一的排序鍵要用複合游標")
        void nonUniqueKeysNeedComposite() {
            // 價格、銷量、評分都會重複，只比排序值會整批跳過或整批重複
            assertThat(ProductSort.PRICE_ASC.needsCompositeCursor()).isTrue();
            assertThat(ProductSort.PRICE_DESC.needsCompositeCursor()).isTrue();
            assertThat(ProductSort.BEST_SELLING.needsCompositeCursor()).isTrue();
            assertThat(ProductSort.RATING.needsCompositeCursor()).isTrue();
            // id 唯一，自己就足以定位
            assertThat(ProductSort.NEWEST.needsCompositeCursor()).isFalse();
        }

        @Test
        @DisplayName("沒指定時是最新上架")
        void defaultsToNewest() {
            assertThat(ProductSort.parse(null)).isEqualTo(ProductSort.NEWEST);
            assertThat(ProductSort.parse("  ")).isEqualTo(ProductSort.NEWEST);
        }

        @Test
        @DisplayName("大小寫不敏感")
        void caseInsensitive() {
            assertThat(ProductSort.parse("price_asc")).isEqualTo(ProductSort.PRICE_ASC);
            assertThat(ProductSort.parse("Best_Selling")).isEqualTo(ProductSort.BEST_SELLING);
        }

        @Test
        @DisplayName("認不得的排序方式要報錯，不可默默退回預設")
        void unknownSortIsRejected() {
            // 悄悄退回預設的話，使用者選了「價格由低到高」卻看到別的順序，
            // 而選單仍停在他選的那一項——他會以為排序壞了，而我們什麼都不知道
            assertThatThrownBy(() -> ProductSort.parse("cheapest"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("cheapest");
        }
    }
}
