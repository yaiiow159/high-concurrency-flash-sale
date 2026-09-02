package com.flashsale.application.service;

import com.flashsale.application.port.in.CartUseCase;
import com.flashsale.application.port.in.PlaceOrderUseCase;
import com.flashsale.application.port.in.dto.CartView;
import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.ShippingInfo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 從購物車結帳。
 *
 * <p>這一層很薄，下單邏輯完全交給 {@link PlaceOrderUseCase}。
 * 因此測試盯的是它自己多做的那幾件事：<b>冪等的檢查順序</b>、
 * 擋下已下架的品項、以及成功後清空購物車。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("購物車結帳")
class CheckoutServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");
    private static final long USER = 88L;
    private static final long ADDRESS = 4L;
    private static final String REQUEST_ID = "req-1";
    private static final String ORDER_NO = "220956648921890816";

    @Mock
    private CartUseCase cartUseCase;
    @Mock
    private PlaceOrderUseCase placeOrderUseCase;
    @Mock
    private OrderRepository orderRepository;

    @Nested
    @DisplayName("冪等")
    class Idempotency {

        @Test
        @DisplayName("重送同一個 requestId 拿回原訂單，而不是「購物車是空的」")
        void replayReturnsOriginalOrderNotEmptyCartError() {
            // 這是真實踩到的順序問題：第一次結帳成功後購物車已清空，
            // 若「購物車是空的」檢查排在冪等檢查之前，逾時重送會看到那個錯誤。
            // 使用者會以為訂單沒成立而重新加購再下一次——結果買了兩份。
            when(orderRepository.findByRequestId(REQUEST_ID))
                    .thenReturn(Optional.of(placedOrder()));
            when(cartUseCase.view(USER)).thenReturn(CartView.empty());

            OrderView result = service().checkout(USER, REQUEST_ID, ADDRESS);

            assertThat(result.orderNo()).isEqualTo(ORDER_NO);
            verify(placeOrderUseCase, never()).place(any());
        }

        @Test
        @DisplayName("重送時不重複扣庫存，也不重複清購物車")
        void replayHasNoSideEffects() {
            when(orderRepository.findByRequestId(REQUEST_ID))
                    .thenReturn(Optional.of(placedOrder()));

            service().checkout(USER, REQUEST_ID, ADDRESS);

            verify(placeOrderUseCase, never()).place(any());
            verify(cartUseCase, never()).clear(anyLong());
        }
    }

    @Nested
    @DisplayName("結帳前的檢查")
    class Preconditions {

        @Test
        @DisplayName("購物車是空的就拒絕，不建立空訂單")
        void rejectsEmptyCart() {
            givenNoExistingOrder();
            when(cartUseCase.view(USER)).thenReturn(CartView.empty());

            assertThatThrownBy(() -> service().checkout(USER, REQUEST_ID, ADDRESS))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.CART_EMPTY);
        }

        @Test
        @DisplayName("已下架的品項在這裡就擋下，錯誤訊息說得出是哪一件")
        void rejectsUnpurchasableWithSpecificMessage() {
            givenNoExistingOrder();
            when(cartUseCase.view(USER)).thenReturn(new CartView(
                    List.of(item(2001L, true), item(2011L, false)),
                    new BigDecimal("100.00"), 2, 0));

            assertThatThrownBy(() -> service().checkout(USER, REQUEST_ID, ADDRESS))
                    .isInstanceOf(BusinessException.class)
                    // 讓它一路走到庫存扣減才失敗的話，使用者只會看到籠統的
                    // 「庫存不足」，而那甚至不是真正的原因
                    .hasMessageContaining("已下架");

            verify(placeOrderUseCase, never()).place(any());
        }
    }

    @Nested
    @DisplayName("下單與清空")
    class PlaceAndClear {

        @Test
        @DisplayName("購物車的每一個品項都變成訂單行")
        void mapsEveryCartItem() {
            givenNoExistingOrder();
            when(cartUseCase.view(USER)).thenReturn(new CartView(
                    List.of(item(2001L, true), item(2011L, true)),
                    new BigDecimal("200.00"), 4, 0));
            when(placeOrderUseCase.place(any())).thenReturn(OrderView.from(placedOrder()));

            service().checkout(USER, REQUEST_ID, ADDRESS);

            ArgumentCaptor<PlaceOrderUseCase.PlaceOrderCommand> captor =
                    ArgumentCaptor.forClass(PlaceOrderUseCase.PlaceOrderCommand.class);
            verify(placeOrderUseCase).place(captor.capture());

            assertThat(captor.getValue().lines())
                    .extracting(PlaceOrderUseCase.OrderItem::skuId)
                    .containsExactly(2001L, 2011L);
            assertThat(captor.getValue().addressId()).isEqualTo(ADDRESS);
            assertThat(captor.getValue().requestId()).isEqualTo(REQUEST_ID);
        }

        @Test
        @DisplayName("下單成功後才清空購物車")
        void clearsCartAfterOrder() {
            givenNoExistingOrder();
            when(cartUseCase.view(USER)).thenReturn(new CartView(
                    List.of(item(2001L, true)), new BigDecimal("100.00"), 2, 0));
            when(placeOrderUseCase.place(any())).thenReturn(OrderView.from(placedOrder()));

            service().checkout(USER, REQUEST_ID, ADDRESS);

            verify(cartUseCase).clear(USER);
        }

        @Test
        @DisplayName("下單失敗就不清購物車——清了的話使用者連重試的東西都沒了")
        void keepsCartWhenOrderFails() {
            givenNoExistingOrder();
            when(cartUseCase.view(USER)).thenReturn(new CartView(
                    List.of(item(2001L, true)), new BigDecimal("100.00"), 2, 0));
            when(placeOrderUseCase.place(any()))
                    .thenThrow(new BusinessException(ErrorCode.SOLD_OUT));

            assertThatThrownBy(() -> service().checkout(USER, REQUEST_ID, ADDRESS))
                    .isInstanceOf(BusinessException.class);

            verify(cartUseCase, never()).clear(anyLong());
        }
    }

    // ---- fixtures ----

    private CheckoutService service() {
        return new CheckoutService(cartUseCase, placeOrderUseCase, orderRepository);
    }

    private void givenNoExistingOrder() {
        when(orderRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.empty());
    }

    private static CartView.Item item(Long skuId, boolean purchasable) {
        return new CartView.Item(skuId, 1L, "iPhone 16 Pro", "256G",
                new BigDecimal("100.00"), 2, new BigDecimal("200.00"), purchasable);
    }

    private static Order placedOrder() {
        return Order.place(OrderNo.of(ORDER_NO), USER, REQUEST_ID,
                List.of(new OrderLine(2001L, "iPhone 16 Pro（256G）",
                        new BigDecimal("100.00"), 2, null)),
                new ShippingInfo("車主", "0911222333", "300",
                        "新竹市", "東區", "光復路一段 1 號"),
                NOW);
    }
}
