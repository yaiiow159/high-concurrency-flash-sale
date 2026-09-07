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

@DisplayName("後台訂單管理")
class OrderAdminServiceTest {

    private static final String ORDER_NO = "222633466569687040";
    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("5990.00");
    private static final long ACTIVITY_ID = 1001L;

    private OrderRepository orderRepository;
    private PaymentRepository paymentRepository;
    private ActivityRepository activityRepository;
    private InventoryService inventoryService;
    private EventOutbox eventOutbox;
    private OrderAdminService service;

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
        service = new OrderAdminService(orderRepository, paymentRepository, activityRepository,
                new OrderCloser(orderRepository, inventoryService, eventOutbox),
                SeckillPolicy.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));
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

    private void givenPendingOrder() {
        when(orderRepository.findByOrderNoForUpdate(OrderNo.of(ORDER_NO)))
                .thenReturn(Optional.of(order(OrderStatus.PENDING_PAYMENT)));
    }

    private void assertNothingChanged() {
        verify(orderRepository, never()).update(any());
        verify(inventoryService, never()).restore(any());
        verify(eventOutbox, never()).append(anyList());
    }

    @Test
    @DisplayName("待付款且沒有付款在途：關單、退一般庫存、寫入退庫事件，三件事一起發生")
    void closesPendingOrder() {
        givenPendingOrder();

        OrderView view = service.close(ORDER_NO, "客戶來電取消");

        assertThat(view.status()).isEqualTo("CANCELLED");
        assertThat(view.closeReason()).isEqualTo("客服關單：客戶來電取消");
        verify(orderRepository).update(any());
        // 只有一般那一行直接退；秒殺那行靠事件，不可在這裡動 Redis
        verify(inventoryService).restore(any());
        verify(eventOutbox).append(anyList());
    }

    @Test
    @DisplayName("付款失敗過：可以關——那張付款單已經是終態")
    void closesWhenPreviousPaymentFailed() {
        givenPendingOrder();
        when(paymentRepository.findByOrderNo(OrderNo.of(ORDER_NO))).thenReturn(Optional.of(payment(PaymentStatus.FAILED)));

        assertThat(service.close(ORDER_NO, "test").status()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("付款在途：拒絕——回調用非鎖定讀，我們 commit 之後它才撞版本衝突，連「錢已收到」都會一起回滾")
    void refusesWhenPaymentInFlight() {
        givenPendingOrder();
        when(paymentRepository.findByOrderNo(OrderNo.of(ORDER_NO))).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));

        assertThatThrownBy(() -> service.close(ORDER_NO, "test"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ILLEGAL_ORDER_STATE_TRANSITION);

        assertNothingChanged();
    }

    @Test
    @DisplayName("活動已結算：拒絕——退回的秒殺庫存不會再被算進去，等於永久少賣一件")
    void refusesWhenActivitySettled() {
        givenPendingOrder();
        Instant settledLongAgo = NOW.minus(SeckillPolicy.defaults().stockKeyTtlBuffer()).minusSeconds(1);
        when(activityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activityEndedAt(settledLongAgo)));

        assertThatThrownBy(() -> service.close(ORDER_NO, "test"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ILLEGAL_ORDER_STATE_TRANSITION);

        assertNothingChanged();
    }

    @Test
    @DisplayName("已付款：拒絕，什麼都不動——關單會退庫存卻不退錢")
    void refusesPaidOrder() {
        when(orderRepository.findByOrderNoForUpdate(OrderNo.of(ORDER_NO)))
                .thenReturn(Optional.of(order(OrderStatus.PAID)));

        assertThatThrownBy(() -> service.close(ORDER_NO, "誤按"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ILLEGAL_ORDER_STATE_TRANSITION);

        assertNothingChanged();
    }

    @Test
    @DisplayName("原因必填：沒有原因的關單事後沒人說得出為什麼")
    void requiresReason() {
        assertThatThrownBy(() -> service.close(ORDER_NO, "  "))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PARAMETER);

        verify(orderRepository, never()).findByOrderNoForUpdate(any());
    }

    @Test
    @DisplayName("不存在：ORDER_NOT_FOUND")
    void notFound() {
        when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.close(ORDER_NO, "test"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("搜尋：空白條件視為沒有條件，總筆數一起回")
    void searchNormalizesBlankFilters() {
        when(orderRepository.search(any(), anyInt(), anyInt())).thenReturn(List.of(order(OrderStatus.PAID)));
        when(orderRepository.countSearch(any())).thenReturn(42L);

        var page = service.search("  ", null, "", 0, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(42L);
        verify(orderRepository).search(new OrderRepository.SearchCriteria(null, null, null), 20, 0);
    }

    @Test
    @DisplayName("搜尋：打錯的狀態是參數錯誤，不是一頁空的")
    void searchRejectsUnknownStatus() {
        assertThatThrownBy(() -> service.search(null, null, "PAYED", 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PARAMETER);
    }
}
