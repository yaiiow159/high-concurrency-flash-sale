package com.flashsale.application.service;

import com.flashsale.application.port.out.InventoryService;
import com.flashsale.application.port.out.PaymentGateway;
import com.flashsale.application.port.out.PaymentMetrics;
import com.flashsale.application.port.out.PaymentRepository;
import com.flashsale.application.port.out.ReturnRequestRepository;
import com.flashsale.domain.aftersales.ReturnLine;
import com.flashsale.domain.aftersales.ReturnNo;
import com.flashsale.domain.aftersales.ReturnReason;
import com.flashsale.domain.aftersales.ReturnRequest;
import com.flashsale.domain.aftersales.ReturnStatus;
import com.flashsale.domain.aftersales.event.RefundRequestedEvent;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.payment.Payment;
import com.flashsale.domain.payment.PaymentNo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退款執行（ADR-0031）。
 *
 * <p>這裡的每一條都在守同一件事：**帳上的「已退款」必須代表錢真的出去了**。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("退款執行")
class RefundExecutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");
    private static final String ORDER_NO = "220956648921890304";
    private static final String RETURN_NO = "RMA-220956648921890111";

    @Mock private PaymentRepository paymentRepository;
    @Mock private ReturnRequestRepository returnRepository;
    @Mock private PaymentGateway paymentGateway;
    @Mock private InventoryService inventoryService;
    @Mock private PaymentMetrics metrics;

    private RefundExecutionService service;

    @BeforeEach
    void setUp() {
        service = new RefundExecutionService(paymentRepository, returnRepository, paymentGateway,
                inventoryService, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
        when(inventoryService.restore(any())).thenReturn(true);
    }

    private ReturnRequest givenReturn(ReturnStatus status) {
        ReturnRequest request = ReturnRequest.restore(1L, ReturnNo.of(RETURN_NO),
                OrderNo.of(ORDER_NO), 88L, "req-1", ReturnReason.CHANGED_MIND, null, false,
                List.of(ReturnLine.of(1L, "商品一", new BigDecimal("990"), 1)),
                status, null, NOW, NOW, null, NOW, null, 0L);
        when(returnRepository.findByReturnNo(any())).thenReturn(Optional.of(request));
        return request;
    }

    private void givenPayment() {
        Payment payment = Payment.initiate(PaymentNo.of("PAY-220956648921890816"),
                OrderNo.of(ORDER_NO), 88L, new BigDecimal("990"), NOW);
        payment.markSucceeded("GW-TXN-001", NOW);
        when(paymentRepository.findByOrderNo(any())).thenReturn(Optional.of(payment));
    }

    private RefundRequestedEvent event(ReturnRequest request) {
        return RefundRequestedEvent.of(request, NOW);
    }

    @Test
    @DisplayName("閘道成功才把退貨單結算成 REFUNDED")
    void settlesOnlyAfterGatewaySucceeds() {
        ReturnRequest request = givenReturn(ReturnStatus.REFUNDING);
        givenPayment();
        when(paymentGateway.refund(any(), any(), anyString()))
                .thenReturn(PaymentGateway.RefundOutcome.success("GW-REFUND-1"));

        service.execute(event(request));

        assertThat(request.status()).isEqualTo(ReturnStatus.REFUNDED);
        assertThat(request.refundedAt()).isEqualTo(NOW);
        verify(returnRepository).update(request);
        verify(metrics).recordRefund(true);
    }

    @Test
    @DisplayName("閘道失敗：退貨單留在 REFUNDING，且錯誤必須可重試")
    void leavesReturnAwaitingSettlementWhenGatewayFails() {
        ReturnRequest request = givenReturn(ReturnStatus.REFUNDING);
        givenPayment();
        when(paymentGateway.refund(any(), any(), anyString()))
                .thenReturn(PaymentGateway.RefundOutcome.failure("閘道逾時"));

        assertThatThrownBy(() -> service.execute(event(request)))
                .isInstanceOf(BusinessException.class)
                // 不可重試的話訊息會直接進死信，而補送排程靠 REFUNDING 才找得到它
                .satisfies(e -> assertThat(((BusinessException) e).errorCode().retryable()).isTrue());

        // 留在 REFUNDING 正是補送排程的入口；寫成 REFUNDED 就再也沒有東西記得這筆錢沒出去
        assertThat(request.status()).isEqualTo(ReturnStatus.REFUNDING);
        verify(returnRepository, never()).update(any());
    }

    @Test
    @DisplayName("已結算的退貨單不再呼叫閘道——重放整個 topic 也不會重複退錢")
    void skipsWhenAlreadySettled() {
        // auto-offset-reset=earliest：新的 consumer group 上線會重放全部歷史事件（鐵則 4）
        ReturnRequest request = givenReturn(ReturnStatus.REFUNDED);

        service.execute(event(request));

        verify(paymentGateway, never()).refund(any(), any(), anyString());
        verify(inventoryService, never()).restore(any());
        verify(returnRepository, never()).update(any());
    }

    @Test
    @DisplayName("退貨單不存在：拋出而不是安靜略過——那代表資料真的出了問題")
    void failsWhenReturnMissing() {
        ReturnRequest request = givenReturn(ReturnStatus.REFUNDING);
        when(returnRepository.findByReturnNo(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(event(request)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RETURN_REQUEST_NOT_FOUND);
    }

    @Test
    @DisplayName("庫存回補在結算之前——中途掛掉時退貨單還留著線索")
    void restocksBeforeSettling() {
        ReturnRequest request = givenReturn(ReturnStatus.REFUNDING);
        givenPayment();
        when(paymentGateway.refund(any(), any(), anyString()))
                .thenReturn(PaymentGateway.RefundOutcome.success("GW-REFUND-1"));
        when(inventoryService.restore(any())).thenThrow(new IllegalStateException("倉儲暫時無法連線"));

        assertThatThrownBy(() -> service.execute(event(request)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(request.status()).isEqualTo(ReturnStatus.REFUNDING);
        verify(metrics, never()).recordRefund(eq(true));
    }
}
