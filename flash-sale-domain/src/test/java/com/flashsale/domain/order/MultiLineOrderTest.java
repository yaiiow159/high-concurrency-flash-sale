package com.flashsale.domain.order;

import com.flashsale.domain.order.event.OrderCancelledEvent;
import com.flashsale.domain.order.event.OrderCreatedEvent;
import com.flashsale.domain.shared.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多品項訂單的行為測試。
 *
 * <p>{@code OrderTest} 從單品項時代遷移而來、斷言未改，驗證的是<b>等價性</b>；
 * 這裡驗證的是重構<b>新帶來</b>的能力——那些在單品項模型下根本無法表達的情境。
 */
@DisplayName("多品項訂單")
class MultiLineOrderTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

    @Nested
    @DisplayName("金額與數量")
    class AmountsAndQuantities {

        @Test
        @DisplayName("總額是各行小計的加總")
        void totalIsSumOfSubtotals() {
            Order order = threeLineOrder();

            // 100×2 + 250×1 + 39.5×4 = 608.00
            assertThat(order.totalAmount()).isEqualByComparingTo(new BigDecimal("608.00"));
            assertThat(order.totalQuantity()).isEqualTo(7);
        }

        @Test
        @DisplayName("小計由單價與數量推導，不獨立儲存——避免兩個真實來源")
        void subtotalIsDerived() {
            OrderLine line = new OrderLine(1L, "商品", new BigDecimal("33.33"), 3, null);

            assertThat(line.subtotal()).isEqualByComparingTo(new BigDecimal("99.99"));
        }

        @Test
        @DisplayName("訂單至少要有一條行")
        void rejectsEmptyOrder() {
            assertThatThrownBy(() -> Order.place(
                    OrderNo.of("20260901001"), 88L, "req-1", List.of(), NOW))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("行數量必須為正——否則會變成庫存回補漏洞")
        void rejectsNonPositiveLineQuantity() {
            assertThatThrownBy(() -> new OrderLine(1L, "商品", BigDecimal.TEN, 0, null))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("跨活動的庫存歸屬")
    class ActivityAttribution {

        @Test
        @DisplayName("同一訂單可含多個活動的行，各自獨立計量")
        void countsQuantityPerActivity() {
            Order order = Order.place(OrderNo.of("20260901002"), 88L, "req-2", List.of(
                    new OrderLine(1L, "秒殺商品甲", new BigDecimal("100.00"), 2, 5001L),
                    new OrderLine(2L, "秒殺商品乙", new BigDecimal("200.00"), 3, 5002L),
                    new OrderLine(3L, "一般商品", new BigDecimal("50.00"), 1, null)
            ), NOW);

            assertThat(order.quantityFromActivity(5001L)).isEqualTo(2);
            assertThat(order.quantityFromActivity(5002L)).isEqualTo(3);
            assertThat(order.quantityFromActivity(9999L)).isZero();
        }

        /**
         * 這條是重構的核心價值。
         *
         * <p>單品項時代的取消事件只帶一個 {@code activityId + quantity}，
         * 多品項下會漏退其餘活動的庫存——而漏退不會有任何錯誤訊息，
         * 那些庫存只會靜靜地消失。
         */
        @Test
        @DisplayName("取消事件為每個活動各產生一筆退庫，不會漏退")
        void cancelEmitsRestorationPerActivity() {
            Order order = Order.place(OrderNo.of("20260901003"), 88L, "req-3", List.of(
                    new OrderLine(1L, "秒殺甲", new BigDecimal("100.00"), 2, 5001L),
                    new OrderLine(2L, "秒殺乙", new BigDecimal("200.00"), 3, 5002L)
            ), NOW);
            order.pullDomainEvents();

            order.cancel("逾時未付款", NOW.plusSeconds(900));

            OrderCancelledEvent event = (OrderCancelledEvent) order.pullDomainEvents().getFirst();
            assertThat(event.restorations())
                    .extracting(OrderCancelledEvent.StockRestoration::activityId,
                            OrderCancelledEvent.StockRestoration::quantity)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(5001L, 2),
                            org.assertj.core.groups.Tuple.tuple(5002L, 3));
        }

        @Test
        @DisplayName("一般商品的行不進退庫清單——它們走資料庫庫存，不是 Redis")
        void normalLinesAreNotRestoredViaRedis() {
            Order order = Order.place(OrderNo.of("20260901004"), 88L, "req-4", List.of(
                    new OrderLine(1L, "一般商品", new BigDecimal("50.00"), 1, null)
            ), NOW);
            order.pullDomainEvents();

            order.cancel("使用者取消", NOW);

            OrderCancelledEvent event = (OrderCancelledEvent) order.pullDomainEvents().getFirst();
            assertThat(event.restorations()).isEmpty();
            assertThat(event.hasStockToRestore()).isFalse();
        }
    }

    @Nested
    @DisplayName("通道")
    class Channels {

        @Test
        @DisplayName("秒殺訂單是只有一條 line 的 Order，不是另一種型別")
        void seckillOrderIsSingleLineOrder() {
            Order order = Order.forSeckill(OrderNo.of("20260901005"), TestActivities.sample(),
                    88L, "req-5", 2, NOW);

            assertThat(order.channel()).isEqualTo(OrderChannel.SECKILL);
            assertThat(order.lines()).hasSize(1);
            assertThat(order.soleLine().sourceActivityId()).isEqualTo(1001L);
        }

        @Test
        @DisplayName("一般訂單標記為 NORMAL")
        void normalOrderIsMarkedAsSuch() {
            assertThat(threeLineOrder().channel()).isEqualTo(OrderChannel.NORMAL);
        }

        @Test
        @DisplayName("多行訂單沒有唯一的一條 line，強行取用要明確失敗")
        void soleLineRejectsMultiLineOrder() {
            assertThatThrownBy(() -> threeLineOrder().soleLine())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("事件契約")
    class EventContract {

        @Test
        @DisplayName("建立事件帶 schemaVersion——部署當下佇列裡還有舊格式訊息")
        void createdEventCarriesSchemaVersion() {
            Order order = threeLineOrder();

            OrderCreatedEvent event = (OrderCreatedEvent) order.pullDomainEvents().getFirst();
            assertThat(event.schemaVersion()).isEqualTo(OrderCreatedEvent.SCHEMA_VERSION);
            assertThat(event.lines()).hasSize(3);
        }

        @Test
        @DisplayName("事件攜帶商品快照，消費端不需要反查——反查會拿到已變動的資料")
        void eventCarriesSnapshot() {
            Order order = threeLineOrder();

            OrderCreatedEvent event = (OrderCreatedEvent) order.pullDomainEvents().getFirst();
            assertThat(event.lines().getFirst().skuSnapshot()).isEqualTo("商品甲");
            assertThat(event.lines().getFirst().unitPrice())
                    .isEqualByComparingTo(new BigDecimal("100.00"));
        }
    }

    private static Order threeLineOrder() {
        return Order.place(OrderNo.of("20260901000"), 88L, "req-0", List.of(
                new OrderLine(1L, "商品甲", new BigDecimal("100.00"), 2, null),
                new OrderLine(2L, "商品乙", new BigDecimal("250.00"), 1, null),
                new OrderLine(3L, "商品丙", new BigDecimal("39.50"), 4, null)
        ), NOW);
    }

    /** 測試用的活動樣本，與 OrderTest 保持一致。 */
    private static final class TestActivities {
        static com.flashsale.domain.activity.SeckillActivity sample() {
            return com.flashsale.domain.activity.SeckillActivity.builder()
                    .id(1001L)
                    .skuId(2001L)
                    .productName("測試商品")
                    .seckillPrice(new BigDecimal("29900.00"))
                    .totalStock(1000)
                    .perUserLimit(2)
                    .period(Instant.parse("2026-09-01T09:00:00Z"), Instant.parse("2026-09-01T12:00:00Z"))
                    .status(com.flashsale.domain.activity.ActivityStatus.ONLINE)
                    .build();
        }
    }
}
