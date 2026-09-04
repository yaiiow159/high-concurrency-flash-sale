package com.flashsale.application.service;

import com.flashsale.application.port.in.PlaceOrderUseCase;
import com.flashsale.application.port.in.PlaceOrderUseCase.OrderItem;
import com.flashsale.application.port.in.PlaceOrderUseCase.PlaceOrderCommand;
import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.application.port.out.AddressRepository;
import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.application.port.out.InventoryService;
import com.flashsale.application.port.out.OrderNoGenerator;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.application.port.out.PromotionRepository;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.ProductStatus;
import com.flashsale.domain.catalog.Sku;
import com.flashsale.domain.catalog.SkuSpec;
import com.flashsale.domain.identity.Address;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderChannel;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.ShippingInfo;
import com.flashsale.domain.promotion.Coupon;
import com.flashsale.domain.promotion.CouponStatus;
import com.flashsale.domain.promotion.DiscountType;
import com.flashsale.domain.promotion.Promotion;
import com.flashsale.domain.promotion.PromotionRule;
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
    private static final long ADDRESS = 7L;
    private static final ShippingInfo SHIPPING = new ShippingInfo(
            "王小明", "0912345678", "110", "臺北市", "信義區", "市府路 1 號");

    @Mock
    private ProductRepository productRepository;
    @Mock
    private AddressRepository addressRepository;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderNoGenerator orderNoGenerator;
    @Mock
    private EventOutbox eventOutbox;
    @Mock
    private PromotionRepository promotionRepository;

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
    @DisplayName("收貨地址快照")
    class Shipping {

        @Test
        @DisplayName("訂單存的是地址內容的快照，不是 addressId")
        void snapshotsAddressIntoOrder() {
            givenCatalog();
            givenDeductSucceeds();
            givenOrderSaves();

            service().place(command(new OrderItem(SKU_A, 1)));

            ShippingInfo shipping = capturedOrder().shippingInfo();
            assertThat(shipping).isNotNull();
            assertThat(shipping.recipientName()).isEqualTo("王小明");
            assertThat(shipping.fullAddress()).isEqualTo("110 臺北市信義區市府路 1 號");
        }

        @Test
        @DisplayName("用別人的 addressId 下單：直接拒絕，且不先扣庫存再回滾")
        void refusesForeignAddress() {
            givenCatalog();
            when(addressRepository.findById(ADDRESS)).thenReturn(Optional.of(Address.restore(
                    ADDRESS, 999L, "別人", "0912345678", "110",
                    "臺北市", "信義區", "市府路 1 號", true, NOW)));

            assertThatThrownBy(() -> service().place(command(new OrderItem(SKU_A, 1))))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ADDRESS_NOT_FOUND);

            // 地址檢查在扣庫存之前：回滾雖然也能救，但那會白白撞一次庫存熱點
            verify(inventoryService, never()).deduct(any());
        }

        @Test
        @DisplayName("地址不存在時拒絕，不會建出一張寄不出去的訂單")
        void refusesMissingAddress() {
            givenCatalog();
            when(addressRepository.findById(ADDRESS)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service().place(command(new OrderItem(SKU_A, 1))))
                    .isInstanceOf(BusinessException.class);

            verify(orderRepository, never()).saveIfAbsent(any());
        }

        @Test
        @DisplayName("沒有 addressId 的指令在入口就被擋下")
        void requiresAddressId() {
            assertThatThrownBy(() ->
                    new PlaceOrderCommand(USER, "req-1", null, List.of(new OrderItem(SKU_A, 1))))
                    .isInstanceOf(NullPointerException.class);
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
            givenAddress();
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
            givenAddress();
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
            assertThatThrownBy(() -> new PlaceOrderCommand(USER, "req-1", ADDRESS, List.of()))
                    .isInstanceOf(BusinessException.class);

            List<OrderItem> tooMany = java.util.stream.IntStream.rangeClosed(1, 51)
                    .mapToObj(i -> new OrderItem((long) i, 1))
                    .toList();
            assertThatThrownBy(() -> new PlaceOrderCommand(USER, "req-1", ADDRESS, tooMany))
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
            assertThatThrownBy(() ->
                    new PlaceOrderCommand(USER, " ", ADDRESS, List.of(new OrderItem(SKU_A, 1))))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("優惠")
    class Promotions {

        @Test
        @DisplayName("折扣進訂單快照，而且是明細不是總額")
        void discountsAreSnapshottedAsLineItems() {
            givenCatalog();
            givenDeductSucceeds();
            givenOrderSaves();
            givenPromotions(orderDiscount(1L, "滿兩萬折兩千", "20000", "2000"));

            service().place(command(new OrderItem(SKU_A, 1)));

            Order order = capturedOrder();
            assertThat(order.discounts()).singleElement()
                    .satisfies(discount -> {
                        assertThat(discount.name()).isEqualTo("滿兩萬折兩千");
                        assertThat(discount.amount()).isEqualByComparingTo("2000.00");
                        // sourceId 保留，供追溯；但名稱是快照，優惠改名不影響歷史訂單
                        assertThat(discount.sourceId()).isEqualTo(1L);
                    });
            assertThat(order.totalAmount()).isEqualByComparingTo("27900.00");
        }

        @Test
        @DisplayName("折後金額要分攤回每一行——退款按那個數字退")
        void allocationIsWrittenBackToLines() {
            givenCatalog();
            givenDeductSucceeds();
            givenOrderSaves();
            givenPromotions(orderDiscount(1L, "滿三萬折兩千", "30000", "2000"));

            service().place(command(new OrderItem(SKU_A, 1), new OrderItem(SKU_B, 1)));

            Order order = capturedOrder();
            BigDecimal allocated = order.lines().stream()
                    .map(OrderLine::allocatedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            // 加總必須等於折後應付，否則退款會按一組數字算、收款按另一組
            assertThat(allocated).isEqualByComparingTo(order.totalAmount());
            assertThat(allocated).isEqualByComparingTo("63800.00");
        }

        @Test
        @DisplayName("未達門檻時不折，也不核銷券——券必須還在使用者手上")
        void couponIsNotConsumedWhenItDoesNotApply() {
            givenCatalog();
            givenDeductSucceeds();
            givenOrderSaves();
            givenPromotions();
            givenCoupon(COUPON, couponRule(9L, "滿十萬折五千", "100000", "5000"));

            service().place(commandWithCoupon(new OrderItem(SKU_A, 1)));

            assertThat(capturedOrder().discounts()).isEmpty();
            verify(promotionRepository, never()).redeem(any(), any(), any());
        }

        @Test
        @DisplayName("券折到錢就核銷，而且核銷失敗要讓整筆下單失敗")
        void redeemFailureFailsTheOrder() {
            givenCatalog();
            givenDeductSucceeds();
            givenOrderSaves();
            givenPromotions();
            givenCoupon(COUPON, couponRule(9L, "折五千", "0", "5000"));
            // 條件式 UPDATE 影響 0 列 = 這張券已經被另一個並行請求用掉了
            when(promotionRepository.redeem(any(), any(), any())).thenReturn(false);

            assertThatThrownBy(() -> service().place(commandWithCoupon(new OrderItem(SKU_A, 1))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> assertThat(((BusinessException) thrown).errorCode())
                            .isEqualTo(ErrorCode.COUPON_ALREADY_USED));
        }

        @Test
        @DisplayName("別人的券當作不存在——回「不是你的」等於確認這個券號有效")
        void othersCouponLooksMissing() {
            givenCatalog();
            givenDeductSucceeds();
            givenOrderSaves();
            givenPromotions();
            when(promotionRepository.findCoupon(COUPON)).thenReturn(Optional.of(
                    Coupon.restore(COUPON, USER + 1, 9L, "CODE-9",
                            CouponStatus.ISSUED, NOW.plusSeconds(86400), null)));

            assertThatThrownBy(() -> service().place(commandWithCoupon(new OrderItem(SKU_A, 1))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> assertThat(((BusinessException) thrown).errorCode())
                            .isEqualTo(ErrorCode.COUPON_NOT_FOUND));
        }

        @Test
        @DisplayName("試算不建訂單、不扣庫存、不核銷券")
        void previewChangesNothing() {
            givenCatalog();
            givenPromotions(orderDiscount(1L, "滿兩萬折兩千", "20000", "2000"));

            var preview = service().preview(new PlaceOrderUseCase.PreviewCommand(
                    USER, List.of(new OrderItem(SKU_A, 1)), null));

            assertThat(preview.subtotal()).isEqualByComparingTo("29900.00");
            assertThat(preview.totalDiscount()).isEqualByComparingTo("2000.00");
            assertThat(preview.payable()).isEqualByComparingTo("27900.00");
            verify(inventoryService, never()).deduct(any());
            verify(orderRepository, never()).saveIfAbsent(any());
            verify(promotionRepository, never()).redeem(any(), any(), any());
        }
    }

    // ---- fixtures ----

    private static final long COUPON = 55L;

    private void givenPromotions(Promotion... promotions) {
        when(promotionRepository.findActivePromotions(any())).thenReturn(List.of(promotions));
    }

    private void givenCoupon(long couponId, Promotion rule) {
        when(promotionRepository.findCoupon(couponId)).thenReturn(Optional.of(
                Coupon.restore(couponId, USER, rule.id(), "CODE-" + couponId,
                        CouponStatus.ISSUED, NOW.plusSeconds(86400), null)));
        when(promotionRepository.findPromotionById(rule.id())).thenReturn(Optional.of(rule));
        when(promotionRepository.redeem(any(), any(), any())).thenReturn(true);
    }

    private static Promotion couponRule(long id, String name, String threshold, String value) {
        return rule(id, name, DiscountType.COUPON, threshold, value);
    }

    private static Promotion orderDiscount(long id, String name, String threshold, String value) {
        return rule(id, name, DiscountType.ORDER_DISCOUNT, threshold, value);
    }

    private static Promotion rule(long id, String name, DiscountType type,
                                  String threshold, String value) {
        return Promotion.of(id, name, type, PromotionRule.FIXED_AMOUNT,
                new BigDecimal(threshold), new BigDecimal(value), null,
                NOW.minusSeconds(3600), NOW.plusSeconds(3600), true);
    }

    private static PlaceOrderCommand commandWithCoupon(OrderItem... items) {
        return new PlaceOrderCommand(USER, "req-1", ADDRESS, List.of(items), COUPON);
    }


    private OrderPlacementService service() {
        return new OrderPlacementService(productRepository, addressRepository, inventoryService,
                orderRepository, orderNoGenerator, eventOutbox, promotionRepository, CLOCK);
    }

    private static PlaceOrderCommand command(OrderItem... items) {
        return new PlaceOrderCommand(USER, "req-1", ADDRESS, List.of(items));
    }

    private void givenCatalog() {
        givenAddress();
        when(orderRepository.findByRequestId("req-1")).thenReturn(Optional.empty());
        when(orderNoGenerator.next()).thenReturn(OrderNo.of(ORDER_NO));
        when(productRepository.findBySkuId(SKU_A))
                .thenReturn(Optional.of(product(ProductStatus.ON_SHELF)));
        when(productRepository.findBySkuId(SKU_B))
                .thenReturn(Optional.of(product(ProductStatus.ON_SHELF)));
    }

    private void givenAddress() {
        when(addressRepository.findById(ADDRESS)).thenReturn(Optional.of(Address.restore(
                ADDRESS, USER, "王小明", "0912345678", "110",
                "臺北市", "信義區", "市府路 1 號", true, NOW)));
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
                        new BigDecimal("29900.00"), 1, null)), SHIPPING, NOW);
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
