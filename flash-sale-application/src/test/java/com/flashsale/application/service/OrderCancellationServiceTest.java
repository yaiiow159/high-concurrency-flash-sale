package com.flashsale.application.service;

import com.flashsale.application.config.SeckillPolicy;
import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.application.port.out.InventoryService;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.PaymentRepository;
import com.flashsale.domain.activity.ActivityStatus;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderChannel;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.OrderStatus;
import com.flashsale.domain.payment.Payment;
import com.flashsale.domain.payment.PaymentNo;
import com.flashsale.domain.payment.PaymentStatus;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("買家取消訂單")
class OrderCancellationServiceTest {

    private static final String ORDER_NO = "222633466569687040";
    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("5990.00");
    private static final long ACTIVITY_ID = 1001L;
    private static final Long OWNER = 7L;

    private OrderRepository orderRepository;
    private PaymentRepository paymentRepository;
    private ActivityRepository activityRepository;
    private InventoryService inventoryService;
    private EventOutbox eventOutbox;
    private OrderCancellationService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        activityRepository = mock(ActivityRepository.class);
        inventoryService = mock(InventoryService.class);
        eventOutbox = mock(EventOutbox.class);
        when(orderRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentRepository.findByOrderNo(any())).thenReturn(Optional.empty());
        when(activityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activityEndedAt(NOW.minusSeconds(60))));
        service = new OrderCancellationService(orderRepository,
                new ManualCloseGuard(paymentRepository, activityRepository, SeckillPolicy.defaults()),
                new OrderCloser(orderRepository, inventoryService, eventOutbox),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 一行秒殺、一行一般：關單時只有一般那行要立刻退庫，秒殺那行走事件。 */
    private static Order order(OrderStatus status) {
        return Order.restore(OrderNo.of(ORDER_NO), 7L, OrderChannel.NORMAL, "req-1",
                List.of(new OrderLine(2001L, "秒殺商品", AMOUNT, 1, ACTIVITY_ID),
                        new OrderLine(2002L, "一般商品", AMOUNT, 2, null)),
                AMOUNT.multiply(BigDecimal.valueOf(3)), null, status, NOW.minusSeconds(600), null, null, 0L);
    }

    private static SeckillActivity activityEndedAt(Instant endAt) {
        return SeckillActivity.builder()
                .id(ACTIVITY_ID).skuId(2001L).productName("秒殺商品").seckillPrice(AMOUNT)
                .totalStock(100).perUserLimit(1).period(endAt.minusSeconds(3600), endAt)
                .status(ActivityStatus.ONLINE).version(0L).build();
    }

    private static Payment payment(PaymentStatus status) {
        return Payment.restore(1L, PaymentNo.of("PAY-222633468847194112"), OrderNo.of(ORDER_NO), 7L, AMOUNT, status,
                null, NOW.minusSeconds(60), null, null, BigDecimal.ZERO, 0L);
    }

    private void givenOrder(OrderStatus status) {
        when(orderRepository.findByOrderNoForUpdate(OrderNo.of(ORDER_NO)))
                .thenReturn(Optional.of(order(status)));
    }

    private void assertNothingChanged() {
        verify(orderRepository, never()).update(any());
        verify(inventoryService, never()).restore(any());
        verify(eventOutbox, never()).append(anyList());
    }

    @Test
    @DisplayName("待付款：關單、退一般庫存、寫入秒殺退庫事件——與逾時關單是同一條路")
    void cancelsPendingOrder() {
        givenOrder(OrderStatus.PENDING_PAYMENT);

        OrderView view = service.cancel(ORDER_NO, OWNER);

        assertThat(view.status()).isEqualTo("CANCELLED");
        assertThat(view.closeReason()).isEqualTo("買家取消訂單");
        verify(orderRepository).update(any());
        // 只有一般那一行直接退；秒殺那行靠事件，不可在交易裡動 Redis
        verify(inventoryService).restore(any());
        verify(eventOutbox).append(anyList());
    }

    @Test
    @DisplayName("以行鎖取得訂單——連點兩下時第二個請求要排在第一個後面，而不是退兩次庫")
    void locksTheOrderRow() {
        givenOrder(OrderStatus.PENDING_PAYMENT);

        service.cancel(ORDER_NO, OWNER);

        verify(orderRepository).findByOrderNoForUpdate(OrderNo.of(ORDER_NO));
        verify(orderRepository, never()).findByOrderNo(any());
    }

    @Test
    @DisplayName("已經取消過：拒絕且不再退庫——重複退庫就是憑空多出庫存")
    void secondCancelDoesNotRestoreAgain() {
        givenOrder(OrderStatus.CANCELLED);

        assertThatThrownBy(() -> service.cancel(ORDER_NO, OWNER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ILLEGAL_ORDER_STATE_TRANSITION);

        assertNothingChanged();
    }

    @Test
    @DisplayName("別人的訂單：回「訂單不存在」，什麼都不動——區分開來等於提供一支訂單號枚舉的 API")
    void refusesOthersOrder() {
        givenOrder(OrderStatus.PENDING_PAYMENT);

        assertThatThrownBy(() -> service.cancel(ORDER_NO, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ORDER_NOT_FOUND);

        assertNothingChanged();
    }

    @Test
    @DisplayName("付款在途：拒絕——閘道隨後回報成功會撞上版本衝突，連「錢已收到」都一起回滾")
    void refusesWhenPaymentInFlight() {
        givenOrder(OrderStatus.PENDING_PAYMENT);
        when(paymentRepository.findByOrderNo(OrderNo.of(ORDER_NO)))
                .thenReturn(Optional.of(payment(PaymentStatus.PENDING)));

        assertThatThrownBy(() -> service.cancel(ORDER_NO, OWNER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ILLEGAL_ORDER_STATE_TRANSITION);

        assertNothingChanged();
    }

    @Test
    @DisplayName("付款失敗過：可以取消——那張付款單已經是終態")
    void cancelsWhenPreviousPaymentFailed() {
        givenOrder(OrderStatus.PENDING_PAYMENT);
        when(paymentRepository.findByOrderNo(OrderNo.of(ORDER_NO)))
                .thenReturn(Optional.of(payment(PaymentStatus.FAILED)));

        assertThat(service.cancel(ORDER_NO, OWNER).status()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("已付款：拒絕——取消會退庫存卻不退錢，那要走退貨")
    void refusesPaidOrder() {
        givenOrder(OrderStatus.PAID);

        assertThatThrownBy(() -> service.cancel(ORDER_NO, OWNER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ILLEGAL_ORDER_STATE_TRANSITION);

        assertNothingChanged();
    }

    @Test
    @DisplayName("活動已結算：拒絕——退回的秒殺庫存不會再被算進去，等於永久少賣一件")
    void refusesWhenActivitySettled() {
        givenOrder(OrderStatus.PENDING_PAYMENT);
        Instant settledLongAgo = NOW.minus(SeckillPolicy.defaults().stockKeyTtlBuffer()).minusSeconds(1);
        when(activityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activityEndedAt(settledLongAgo)));

        assertThatThrownBy(() -> service.cancel(ORDER_NO, OWNER))
                .isInstanceOf(BusinessException.class);

        assertNothingChanged();
    }
}
