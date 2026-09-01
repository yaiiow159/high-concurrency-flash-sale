package com.flashsale.application.service;

import com.flashsale.application.port.in.PlaceOrderUseCase.OrderItem;
import com.flashsale.application.port.in.PlaceOrderUseCase.PlaceOrderCommand;
import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.application.port.out.InventoryService;
import com.flashsale.application.port.out.OrderNoGenerator;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.ProductStatus;
import com.flashsale.domain.catalog.Sku;
import com.flashsale.domain.catalog.SkuSpec;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderChannel;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.domain.stock.StockDeductionOutcome;
import com.flashsale.domain.stock.StockDeductionResult;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一般下單。
 *
 * <p>這條通道的全部主張是「同步、單一交易」，因此測試要盯的是：
 * 價格不由呼叫端決定、失敗時不留下半成品、重送不會下第二單。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("一般下單")
class OrderPlacementServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long USER = 42L;
    private static final long SKU_A = 2001L;
    private static final long SKU_B = 2011L;
    private static final String ORDER_NO = "220600000000000001";

    @Mock
    private ProductRepository productRepository;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderNoGenerator orderNoGenerator;
    @Mock
    private EventOutbox eventOutbox;

    @Nested
    @DisplayName("價格由目錄決定")
    class Pricing {

        @Test
        @DisplayName("單價與快照取自目錄，呼叫端只能說要買什麼、幾件")
        void takesPriceFromCatalog() {
            givenCatalog();
            givenDeductSucceeds();
            givenOrderSaves();

            service().place(command(new OrderItem(SKU_A, 2)));

            Order saved = capturedOrder();
            OrderLine line = saved.lines().get(0);
            assertThat(line.unitPrice()).isEqualByComparingTo(new BigDecimal("29900.00"));
            assertThat(line.skuSnapshot()).contains("iPhone 16 Pro").contains("256G");
            assertThat(saved.totalAmount()).isEqualByComparingTo(new BigDecimal("59800.00"));
        }

        @Test
        @DisplayName("多品項各自取自己的價格，總額是小計加總")
        void sumsAcrossLines() {
            givenCatalog();
            givenDeductSucceeds();
            givenOrderSaves();

            service().place(command(new OrderItem(SKU_A, 1), new OrderItem(SKU_B, 1)));

            assertThat(capturedOrder().totalAmount())
                    .isEqualByComparingTo(new BigDecimal("65800.00"));
        }

        @Test
        @DisplayName("訂單行不掛活動——一般訂單扣的是可售池，不是劃撥出去的額度")
        void linesCarryNoActivity() {
            givenCatalog();
            givenDeductSucceeds();
            givenOrderSaves();

            service().place(command(new OrderItem(SKU_A, 1)));

            assertThat(capturedOrder().lines().get(0).sourceActivityId()).isNull();
            assertThat(capturedOrder().channel()).isEqualTo(OrderChannel.NORMAL);
        }
    }

    @Nested
    @DisplayName("失敗時不留半成品")
    class Atomicity {

        @Test
        @DisplayName("任一品項庫存不足就整筆失敗，訂單不會被建立")
        void wholeOrderFailsWhenOneLineIsSoldOut() {
            givenCatalog();
            givenOrderSaves();
            when(inventoryService.deduct(any()))
                    .thenReturn(StockDeductionResult.success(ORDER_NO))
                    .thenReturn(StockDeductionResult.rejected(StockDeductionOutcome.SOLD_OUT));

            assertThatThrownBy(() ->
                    service().place(command(new OrderItem(SKU_A, 1), new OrderItem(SKU_B, 1))))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.SOLD_OUT);

            // 第一行已經扣掉的量由交易回滾復原——這正是同步通道不需要補償的原因。
            // 這裡要驗的是「不會留下一張沒付款也退不了的訂單」。
            verify(orderRepository, never()).saveIfAbsent(any());
            verify(eventOutbox, never()).append(any());
        }

        @Test
        @DisplayName("下架商品不可下單，且錯誤碼要能區分原因")
        void refusesUnpurchasableProduct() {
            when(productRepository.findBySkuId(SKU_A))
                    .thenReturn(Optional.of(product(ProductStatus.OFF_SHELF)));

            assertThatThrownBy(() -> service().place(command(new OrderItem(SKU_A, 1))))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.PRODUCT_NOT_PURCHASABLE);

            // 商品都還沒驗過就先扣庫存，會在下架商品上留下扣減紀錄
            verify(inventoryService, never()).deduct(any());
        }

        @Test
        @DisplayName("不存在的規格回 SKU_NOT_FOUND，不會靜默略過那一行")
        void refusesUnknownSku() {
            when(productRepository.findBySkuId(9999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service().place(command(new OrderItem(9999L, 1))))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.SKU_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("冪等")
    class Idempotency {

        @Test
        @DisplayName("重送同一個 requestId 拿回同一張訂單，且不再扣一次庫存")
        void returnsExistingOrderOnReplay() {
            Order existing = existingOrder();
            when(orderRepository.findByRequestId("req-1")).thenReturn(Optional.of(existing));

            OrderView view = service().place(command(new OrderItem(SKU_A, 1)));

            assertThat(view.orderNo()).isEqualTo(ORDER_NO);
            verify(inventoryService, never()).deduct(any());
            verify(orderRepository, never()).saveIfAbsent(any());
        }

        @Test
        @DisplayName("兩個並行請求撞同一個 requestId：資料庫唯一索引擋下第二個，回傳先寫入的那張")
        void fallsBackToExistingWhenUniqueIndexRejects() {
            givenCatalog();
            givenDeductSucceeds();
            when(orderNoGenerator.next()).thenReturn(OrderNo.of(ORDER_NO));
            // saveIfAbsent 回 empty 代表唯一索引擋下了
            when(orderRepository.saveIfAbsent(any())).thenReturn(Optional.empty());
            when(orderRepository.findByRequestId("req-1"))
                    .thenReturn(Optional.empty(), Optional.of(existingOrder()));

            OrderView view = service().place(command(new OrderItem(SKU_A, 1)));

            assertThat(view.orderNo()).isEqualTo(ORDER_NO);
        }
    }

    @Nested
    @DisplayName("輸入驗證")
    class Validation {

        @Test
        @DisplayName("同一個 SKU 重複出現：在入口就拒絕，而不是讓它在庫存流水的唯一鍵上撞死")
        void rejectsDuplicateSku() {
            assertThatThrownBy(() -> command(new OrderItem(SKU_A, 1), new OrderItem(SKU_A, 2)))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("空訂單與過多品項都拒絕")
        void rejectsEmptyAndOversizedOrders() {
            assertThatThrownBy(() -> new PlaceOrderCommand(USER, "req-1", List.of()))
                    .isInstanceOf(BusinessException.class);

            List<OrderItem> tooMany = java.util.stream.IntStream.rangeClosed(1, 51)
                    .mapToObj(i -> new OrderItem((long) i, 1))
                    .toList();
            assertThatThrownBy(() -> new PlaceOrderCommand(USER, "req-1", tooMany))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("數量必須為正且有上限")
        void rejectsInvalidQuantity() {
            assertThatThrownBy(() -> new OrderItem(SKU_A, 0)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> new OrderItem(SKU_A, 1000)).isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("requestId 不可為空——沒有它就沒有冪等")
        void requiresRequestId() {
            assertThatThrownBy(() -> new PlaceOrderCommand(USER, " ", List.of(new OrderItem(SKU_A, 1))))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ---- fixtures ----

    private OrderPlacementService service() {
        return new OrderPlacementService(productRepository, inventoryService, orderRepository,
                orderNoGenerator, eventOutbox, CLOCK);
    }

    private static PlaceOrderCommand command(OrderItem... items) {
        return new PlaceOrderCommand(USER, "req-1", List.of(items));
    }

    private void givenCatalog() {
        when(orderRepository.findByRequestId("req-1")).thenReturn(Optional.empty());
        when(orderNoGenerator.next()).thenReturn(OrderNo.of(ORDER_NO));
        when(productRepository.findBySkuId(SKU_A))
                .thenReturn(Optional.of(product(ProductStatus.ON_SHELF)));
        when(productRepository.findBySkuId(SKU_B))
                .thenReturn(Optional.of(product(ProductStatus.ON_SHELF)));
    }

    private void givenDeductSucceeds() {
        when(inventoryService.deduct(any())).thenReturn(StockDeductionResult.success(ORDER_NO));
    }

    private void givenOrderSaves() {
        when(orderRepository.saveIfAbsent(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
    }

    private Order capturedOrder() {
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveIfAbsent(captor.capture());
        return captor.getValue();
    }

    private Order existingOrder() {
        return Order.place(OrderNo.of(ORDER_NO), USER, "req-1",
                List.of(new OrderLine(SKU_A, "iPhone 16 Pro（256G）",
                        new BigDecimal("29900.00"), 1, null)), NOW);
    }

    private static Product product(ProductStatus status) {
        return Product.restore(1L, 2L, "iPhone 16 Pro", "Apple", "旗艦機種", status, List.of(
                Sku.restore(SKU_A, 1L, spec("256G"), new BigDecimal("29900.00"), "IP16P-256", status),
                Sku.restore(SKU_B, 1L, spec("512G"), new BigDecimal("35900.00"), "IP16P-512", status)
        ), NOW);
    }

    private static SkuSpec spec(String capacity) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("容量", capacity);
        return SkuSpec.of(attributes);
    }
}
