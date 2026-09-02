package com.flashsale.application.service;

import com.flashsale.application.port.in.CartUseCase.LocalItem;
import com.flashsale.application.port.in.dto.CartView;
import com.flashsale.application.port.out.CartRepository;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.domain.cart.Cart;
import com.flashsale.domain.cart.CartItem;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.ProductStatus;
import com.flashsale.domain.catalog.Sku;
import com.flashsale.domain.catalog.SkuSpec;
import com.flashsale.domain.shared.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 購物車服務。
 *
 * <p>兩條規則必須守住：<b>價格永遠是即時的</b>（存快照會讓使用者
 * 看到舊價格卻被收新價格），以及<b>購物車完全不碰庫存</b>
 * （否則任何人都能靠塞滿購物車凍結全站庫存）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("購物車服務")
class CartServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long USER = 88L;
    private static final long SKU_A = 2001L;
    private static final long SKU_B = 2011L;

    @Mock
    private CartRepository cartRepository;
    @Mock
    private ProductRepository productRepository;

    @Nested
    @DisplayName("價格是即時的，不是快照")
    class LivePricing {

        @Test
        @DisplayName("商家調價後，購物車顯示新價格")
        void reflectsCurrentPrice() {
            givenServerCart(new CartItem(SKU_A, 2, NOW));
            givenCatalog(ProductStatus.ON_SHELF, new BigDecimal("35900.00"));

            CartView view = service().view(USER);

            assertThat(view.items().get(0).unitPrice())
                    .isEqualByComparingTo(new BigDecimal("35900.00"));
            assertThat(view.totalAmount()).isEqualByComparingTo(new BigDecimal("71800.00"));
        }

        @Test
        @DisplayName("小計與總額都用當下價格重算，不依賴任何存下來的數字")
        void recomputesEverySubtotal() {
            givenServerCart(new CartItem(SKU_A, 3, NOW));
            givenCatalog(ProductStatus.ON_SHELF, new BigDecimal("100.00"));

            CartView view = service().view(USER);

            assertThat(view.items().get(0).subtotal()).isEqualByComparingTo("300.00");
            assertThat(view.totalQuantity()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("下架與刪除的商品")
    class Unavailable {

        @Test
        @DisplayName("已下架的品項留在清單裡並標記，不靜默刪掉")
        void keepsButFlagsUnpurchasable() {
            givenServerCart(new CartItem(SKU_A, 1, NOW));
            givenCatalog(ProductStatus.OFF_SHELF, new BigDecimal("100.00"));

            CartView view = service().view(USER);

            assertThat(view.items()).hasSize(1);
            assertThat(view.items().get(0).purchasable()).isFalse();
        }

        @Test
        @DisplayName("已下架的品項不計入總額——顯示一個結不了帳的金額只會造成誤解")
        void excludesUnpurchasableFromTotal() {
            givenServerCart(new CartItem(SKU_A, 1, NOW));
            givenCatalog(ProductStatus.OFF_SHELF, new BigDecimal("100.00"));

            assertThat(service().view(USER).totalAmount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("商品被刪除（查無資料）才移除，並回報移除數讓使用者知道")
        void removesVanishedAndReportsCount() {
            givenServerCart(new CartItem(SKU_A, 1, NOW));
            when(productRepository.findBySkuIds(any())).thenReturn(List.of());

            CartView view = service().view(USER);

            assertThat(view.removedCount()).isEqualTo(1);
            assertThat(view.items()).isEmpty();
            // 讀取時發現的移除要寫回去，否則每次進購物車都會再報一次
            verify(cartRepository).save(any());
        }
    }

    @Nested
    @DisplayName("加入購物車")
    class AddItem {

        @Test
        @DisplayName("完全不碰庫存——否則任何人都能靠塞滿購物車凍結全站庫存")
        void neverTouchesInventory() {
            givenServerCart();
            givenCatalog(ProductStatus.ON_SHELF, new BigDecimal("100.00"));
            when(productRepository.findBySkuId(SKU_A))
                    .thenReturn(Optional.of(product(ProductStatus.ON_SHELF, new BigDecimal("100.00"))));

            service().addItem(USER, SKU_A, 2);

            // CartService 的建構子裡根本沒有 InventoryService——
            // 這條測試守的是「將來也不准加進去」
            assertThat(CartService.class.getDeclaredConstructors()[0].getParameterTypes())
                    .noneMatch(type -> type.getSimpleName().contains("Inventory"));
        }

        @Test
        @DisplayName("加入前先確認可購買，避免累積一堆結帳才失敗的品項")
        void rejectsUnpurchasableSku() {
            givenServerCart();
            when(productRepository.findBySkuId(SKU_A))
                    .thenReturn(Optional.of(product(ProductStatus.OFF_SHELF, new BigDecimal("100.00"))));

            assertThatThrownBy(() -> service().addItem(USER, SKU_A, 1))
                    .isInstanceOf(BusinessException.class);

            verify(cartRepository, never()).save(any());
        }

        @Test
        @DisplayName("不存在的 SKU 直接拒絕")
        void rejectsUnknownSku() {
            givenServerCart();
            when(productRepository.findBySkuId(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service().addItem(USER, 9999L, 1))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("合併本地購物車")
    class Merge {

        @Test
        @DisplayName("不可購買的品項略過而非整批失敗——登入不該因為購物車有下架商品而失敗")
        void skipsUnpurchasableInsteadOfFailing() {
            givenServerCart();
            givenCatalog(ProductStatus.ON_SHELF, new BigDecimal("100.00"));
            when(productRepository.findBySkuId(SKU_A))
                    .thenReturn(Optional.of(product(ProductStatus.ON_SHELF, new BigDecimal("100.00"))));
            when(productRepository.findBySkuId(SKU_B)).thenReturn(Optional.empty());

            service().merge(USER, List.of(new LocalItem(SKU_A, 2), new LocalItem(SKU_B, 1)));

            Cart saved = capturedCart();
            assertThat(saved.skuIds()).containsExactly(SKU_A);
        }

        @Test
        @DisplayName("同一個 SKU 取較大值")
        void takesMaxQuantity() {
            givenServerCart(new CartItem(SKU_A, 5, NOW));
            givenCatalog(ProductStatus.ON_SHELF, new BigDecimal("100.00"));
            when(productRepository.findBySkuId(SKU_A))
                    .thenReturn(Optional.of(product(ProductStatus.ON_SHELF, new BigDecimal("100.00"))));

            service().merge(USER, List.of(new LocalItem(SKU_A, 2)));

            assertThat(capturedCart().items().get(0).quantity()).isEqualTo(5);
        }

        @Test
        @DisplayName("前端送來的不合法數量不會讓整批合併炸掉")
        void toleratesMalformedLocalItems() {
            givenServerCart();
            givenCatalog(ProductStatus.ON_SHELF, new BigDecimal("100.00"));
            when(productRepository.findBySkuId(SKU_A))
                    .thenReturn(Optional.of(product(ProductStatus.ON_SHELF, new BigDecimal("100.00"))));

            // localStorage 是使用者改得動的，數量 0 是完全可能送上來的
            service().merge(USER, List.of(new LocalItem(SKU_A, 0)));

            assertThat(capturedCart().isEmpty()).isTrue();
        }
    }

    // ---- fixtures ----

    private CartService service() {
        return new CartService(cartRepository, productRepository, CLOCK);
    }

    private void givenServerCart(CartItem... items) {
        when(cartRepository.findByUserId(USER))
                .thenReturn(Cart.restore(USER, List.of(items)));
    }

    private void givenCatalog(ProductStatus status, BigDecimal price) {
        when(productRepository.findBySkuIds(any()))
                .thenReturn(List.of(product(status, price)));
    }

    private Cart capturedCart() {
        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        return captor.getValue();
    }

    private static Product product(ProductStatus status, BigDecimal price) {
        return Product.restore(1L, 2L, "iPhone 16 Pro", "Apple", "旗艦機種", status, List.of(
                Sku.restore(SKU_A, 1L, spec("256G"), price, "IP16P-256", status)
        ), NOW);
    }

    private static SkuSpec spec(String capacity) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("容量", capacity);
        return SkuSpec.of(attributes);
    }
}
