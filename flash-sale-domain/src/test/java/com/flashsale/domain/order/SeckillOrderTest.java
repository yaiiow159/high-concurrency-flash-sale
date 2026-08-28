package com.flashsale.domain.order;

import com.flashsale.domain.activity.ActivityStatus;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.order.event.OrderCancelledEvent;
import com.flashsale.domain.order.event.OrderCreatedEvent;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.DomainEvent;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 訂單聚合根的狀態機與事件蒐集測試。 */
class SeckillOrderTest {

    private static final Instant NOW = Instant.parse("2025-06-01T10:30:00Z");

    @Test
    @DisplayName("建立訂單：狀態為待付款，金額由活動計算，並登記 OrderCreatedEvent")
    void createsPendingOrderWithEvent() {
        SeckillOrder order = newOrder();

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.amount()).isEqualByComparingTo(new BigDecimal("59800.00"));

        List<DomainEvent> events = order.pullDomainEvents();
        assertThat(events).hasSize(1).first().isInstanceOf(OrderCreatedEvent.class);
    }

    @Test
    @DisplayName("事件取出後即清空，避免同一事件被寫入 Outbox 兩次")
    void pullingEventsClearsThem() {
        SeckillOrder order = newOrder();

        assertThat(order.pullDomainEvents()).hasSize(1);
        assertThat(order.pullDomainEvents()).isEmpty();
    }

    @Test
    @DisplayName("取消訂單：登記的補償事件必須帶著 requestId，否則退庫無法冪等")
    void cancelEventCarriesRequestIdForIdempotentCompensation() {
        SeckillOrder order = newOrder();
        order.pullDomainEvents();

        order.cancel("逾時未付款", NOW.plus(Duration.ofMinutes(20)));

        List<DomainEvent> events = order.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOfSatisfying(OrderCancelledEvent.class,
                event -> {
                    assertThat(event.requestId()).isEqualTo("req-001");
                    assertThat(event.quantity()).isEqualTo(2);
                });
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "CANCELLED", "FAILED"})
    @DisplayName("終態不可再轉移：付款、取消、失敗都是不可逆的")
    void finalStatesRejectFurtherTransition(OrderStatus finalStatus) {
        SeckillOrder order = restoredOrder(finalStatus);

        assertThatThrownBy(() -> order.cancel("再取消一次", NOW))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_ORDER_STATE_TRANSITION);
    }

    @Test
    @DisplayName("已付款的訂單不可被逾時關單搶走——這會直接造成客訴")
    void paidOrderCannotBeCancelled() {
        SeckillOrder order = newOrder();
        order.pay(NOW.plusSeconds(60));

        assertThatThrownBy(() -> order.cancel("逾時未付款", NOW.plusSeconds(900)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("逾期判定以建立時間加付款期限為界")
    void detectsPaymentExpiry() {
        SeckillOrder order = newOrder();
        Duration window = Duration.ofMinutes(15);

        assertThat(order.isPaymentExpiredAt(NOW.plus(Duration.ofMinutes(14)), window)).isFalse();
        assertThat(order.isPaymentExpiredAt(NOW.plus(Duration.ofMinutes(16)), window)).isTrue();
    }

    @Test
    @DisplayName("已付款的訂單永遠不算逾期")
    void paidOrderIsNeverExpired() {
        SeckillOrder order = newOrder();
        order.pay(NOW.plusSeconds(60));

        assertThat(order.isPaymentExpiredAt(NOW.plus(Duration.ofDays(1)), Duration.ofMinutes(15))).isFalse();
    }

    private static SeckillOrder newOrder() {
        return SeckillOrder.create(OrderNo.of("20250601000001"), activity(), 88L, "req-001", 2, NOW);
    }

    private static SeckillOrder restoredOrder(OrderStatus status) {
        return SeckillOrder.restore(OrderNo.of("20250601000001"), 1001L, 88L, "req-001",
                2, new BigDecimal("59800.00"), status, NOW, null, null, 0L);
    }

    private static SeckillActivity activity() {
        return SeckillActivity.builder()
                .id(1001L)
                .productId(2001L)
                .productName("測試商品")
                .seckillPrice(new BigDecimal("29900.00"))
                .totalStock(1000)
                .perUserLimit(2)
                .period(Instant.parse("2025-06-01T10:00:00Z"), Instant.parse("2025-06-01T12:00:00Z"))
                .status(ActivityStatus.ONLINE)
                .build();
    }
}
