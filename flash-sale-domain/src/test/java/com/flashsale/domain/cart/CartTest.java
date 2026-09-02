package com.flashsale.domain.cart;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("購物車")
class CartTest {

    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(600);
    private static final long USER = 88L;
    private static final long SKU_A = 2001L;
    private static final long SKU_B = 2011L;

    @Nested
    @DisplayName("加入品項")
    class AddItem {

        @Test
        @DisplayName("同一個 SKU 累加數量，不新增一行——按兩次「加入購物車」應該得到 2 件")
        void mergesSameSku() {
            Cart cart = Cart.empty(USER);

            cart.addItem(SKU_A, 1, NOW);
            cart.addItem(SKU_A, 1, LATER);

            assertThat(cart.items()).hasSize(1);
            assertThat(cart.items().get(0).quantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("不同 SKU 各自一行，且維持加入順序")
        void keepsInsertionOrder() {
            Cart cart = Cart.empty(USER);

            cart.addItem(SKU_A, 1, NOW);
            cart.addItem(SKU_B, 1, NOW);

            assertThat(cart.items()).extracting(CartItem::skuId)
                    .containsExactly(SKU_A, SKU_B);
        }

        @Test
        @DisplayName("累加超過上限時夾住而非拋錯——按第三次不該收到錯誤訊息")
        void capsInsteadOfThrowing() {
            Cart cart = Cart.empty(USER);

            cart.addItem(SKU_A, 900, NOW);
            cart.addItem(SKU_A, 900, LATER);

            assertThat(cart.items().get(0).quantity()).isEqualTo(Cart.MAX_QUANTITY_PER_ITEM);
        }

        @Test
        @DisplayName("品項種類達上限時拒絕新增")
        void rejectsBeyondItemLimit() {
            Cart cart = Cart.empty(USER);
            for (int i = 1; i <= Cart.MAX_ITEMS; i++) {
                cart.addItem((long) i, 1, NOW);
            }

            assertThatThrownBy(() -> cart.addItem(9999L, 1, NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.CART_ITEM_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("已在車裡的 SKU 即使達到種類上限也能繼續加量")
        void canStillIncreaseExistingItemAtLimit() {
            Cart cart = Cart.empty(USER);
            for (int i = 1; i <= Cart.MAX_ITEMS; i++) {
                cart.addItem((long) i, 1, NOW);
            }

            cart.addItem(1L, 1, LATER);

            assertThat(cart.items()).hasSize(Cart.MAX_ITEMS);
            assertThat(cart.items().get(0).quantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("非正數與過大的數量都拒絕")
        void rejectsInvalidQuantity() {
            Cart cart = Cart.empty(USER);

            assertThatThrownBy(() -> cart.addItem(SKU_A, 0, NOW))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> cart.addItem(SKU_A, 1000, NOW))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("調整與移除")
    class ChangeAndRemove {

        @Test
        @DisplayName("數量設為 0 等同移除")
        void zeroQuantityRemoves() {
            Cart cart = cartWith(SKU_A, 3);

            cart.changeQuantity(SKU_A, 0, LATER);

            assertThat(cart.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("調整數量是覆寫而非累加——購物車頁的輸入框說 5 就是 5")
        void changeOverwrites() {
            Cart cart = cartWith(SKU_A, 3);

            cart.changeQuantity(SKU_A, 5, LATER);

            assertThat(cart.items().get(0).quantity()).isEqualTo(5);
        }

        @Test
        @DisplayName("調整或移除不存在的品項會明確失敗，不靜默略過")
        void failsOnMissingItem() {
            Cart cart = Cart.empty(USER);

            assertThatThrownBy(() -> cart.changeQuantity(SKU_A, 1, NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.CART_ITEM_NOT_FOUND);
            assertThatThrownBy(() -> cart.removeItem(SKU_A))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("移除下架商品時回傳實際移除數，讓呼叫端能告訴使用者")
        void reportsRemovedCount() {
            Cart cart = Cart.empty(USER);
            cart.addItem(SKU_A, 1, NOW);
            cart.addItem(SKU_B, 1, NOW);

            int removed = cart.removeUnavailable(List.of(SKU_A, 9999L));

            assertThat(removed).as("只算真的在車裡的").isEqualTo(1);
            assertThat(cart.skuIds()).containsExactly(SKU_B);
        }
    }

    @Nested
    @DisplayName("合併本地購物車")
    class Merge {

        @Test
        @DisplayName("同一個 SKU 取較大值，不相加——相加會讓使用者看到從沒按過的數字")
        void takesMaxNotSum() {
            Cart server = cartWith(SKU_A, 2);
            Cart local = cartWith(SKU_A, 2);

            server.mergeFrom(local, LATER);

            assertThat(server.items().get(0).quantity())
                    .as("兩台裝置各加 2 件，要的是 2 不是 4")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("本地數量較大時採用本地的")
        void adoptsLargerLocalQuantity() {
            Cart server = cartWith(SKU_A, 1);

            server.mergeFrom(cartWith(SKU_A, 5), LATER);

            assertThat(server.items().get(0).quantity()).isEqualTo(5);
        }

        @Test
        @DisplayName("伺服器端沒有的品項會被加入")
        void addsNewItems() {
            Cart server = cartWith(SKU_A, 1);

            server.mergeFrom(cartWith(SKU_B, 3), LATER);

            assertThat(server.skuIds()).containsExactlyInAnyOrder(SKU_A, SKU_B);
        }

        @Test
        @DisplayName("超過上限時丟棄多出來的，不讓整次合併失敗——登入不該因購物車太滿而失敗")
        void dropsOverflowInsteadOfFailing() {
            Cart server = Cart.empty(USER);
            for (int i = 1; i <= Cart.MAX_ITEMS; i++) {
                server.addItem((long) i, 1, NOW);
            }
            Cart local = cartWith(9999L, 1);

            server.mergeFrom(local, LATER);

            assertThat(server.items()).hasSize(Cart.MAX_ITEMS);
            assertThat(server.skuIds()).doesNotContain(9999L);
        }

        @Test
        @DisplayName("合併空的本地購物車不改變任何東西")
        void mergingEmptyChangesNothing() {
            Cart server = cartWith(SKU_A, 2);

            server.mergeFrom(Cart.empty(USER), LATER);

            assertThat(server.items()).hasSize(1);
            assertThat(server.items().get(0).quantity()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("購物車不是訂單")
    class NotAnOrder {

        @Test
        @DisplayName("品項只有 SKU 與數量，沒有價格也沒有商品名")
        void carriesNoPriceSnapshot() {
            // 這條測試守的是一個設計決定而非一段邏輯：
            // 購物車一旦存了價格，商家調價後使用者會看到舊價格、
            // 結帳時卻被收新價格。若日後有人往 CartItem 加價格欄位，
            // 這裡會編譯失敗，逼他先讀完 Cart 的 Javadoc。
            CartItem item = new CartItem(SKU_A, 2, NOW);

            assertThat(item.getClass().getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactly("skuId", "quantity", "updatedAt");
        }

        @Test
        @DisplayName("總數量是各品項數量的加總")
        void sumsQuantities() {
            Cart cart = Cart.empty(USER);
            cart.addItem(SKU_A, 2, NOW);
            cart.addItem(SKU_B, 3, NOW);

            assertThat(cart.totalQuantity()).isEqualTo(5);
        }

        @Test
        @DisplayName("清空後為空車，不是 null")
        void clearLeavesEmptyCart() {
            Cart cart = cartWith(SKU_A, 2);

            cart.clear();

            assertThat(cart.isEmpty()).isTrue();
            assertThat(cart.items()).isEmpty();
        }
    }

    // ---- fixtures ----

    private static Cart cartWith(Long skuId, int quantity) {
        Cart cart = Cart.empty(USER);
        cart.addItem(skuId, quantity, NOW);
        return cart;
    }
}
