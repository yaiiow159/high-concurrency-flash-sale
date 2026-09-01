package com.flashsale.application.service;

import com.flashsale.application.port.in.dto.PaymentIntentView;
import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.PaymentGateway;
import com.flashsale.application.port.out.PaymentNoGenerator;
import com.flashsale.application.port.out.PaymentRepository;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.OrderStatus;
import com.flashsale.domain.order.SeckillOrder;
import com.flashsale.domain.payment.Payment;
import com.flashsale.domain.payment.PaymentNo;
import com.flashsale.domain.payment.PaymentStatus;
import com.flashsale.domain.payment.event.PaymentRefundRequiredEvent;
import com.flashsale.domain.payment.event.PaymentSucceededEvent;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.DomainEvent;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 付款流程的單元測試。
 *
 * <p>重點是「錢收了但訂單入不了帳」這個競態。它在功能測試中永遠不會出現——
 * 要重現它必須刻意安排「回調抵達時訂單已被關閉」這個時序，
 * 而那正是這裡用 mock 能精準做到的事。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("付款")
class PaymentApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final Long USER_ID = 42L;
    private static final String ORDER_NO = "220349960435007488";
    private static final String PAYMENT_NO = "PAY-220349960435007499";
    private static final String TXN_ID = "SIM-TXN-abc123";
    private static final BigDecimal AMOUNT = new BigDecimal("29900.00");

    @Mock private PaymentRepository paymentRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private PaymentGateway paymentGateway;
    @Mock private PaymentNoGenerator paymentNoGenerator;
    @Mock private EventOutbox eventOutbox;
    @Mock private PaymentMetrics metrics;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PaymentApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PaymentApplicationService(paymentRepository, orderRepository, paymentGateway,
                paymentNoGenerator, eventOutbox, metrics, eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(paymentNoGenerator.next()).thenReturn(PaymentNo.of(PAYMENT_NO));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.initiate(any()))
                .thenReturn(new PaymentGateway.PaymentIntent("ref-1", "https://pay.example/checkout"));
        when(paymentGateway.verifyCallbackSignature(anyMap())).thenReturn(true);
    }

    @Nested
    @DisplayName("發起付款")
    class Initiate {

        @Test
        @DisplayName("待付款訂單：建立付款單並回傳付款網址")
        void createsPaymentForPendingOrder() {
            givenOrder(OrderStatus.PENDING_PAYMENT);
            when(paymentRepository.findByOrderNo(any())).thenReturn(Optional.empty());

            PaymentIntentView intent = service.initiate(ORDER_NO, USER_ID);

            assertThat(intent.paymentNo()).isEqualTo(PAYMENT_NO);
            assertThat(intent.paymentUrl()).isEqualTo("https://pay.example/checkout");
        }

        @Test
        @DisplayName("金額取自訂單，不接受呼叫端傳入——否則前端就能自己決定要付多少")
        void amountComesFromOrder() {
            givenOrder(OrderStatus.PENDING_PAYMENT);
            when(paymentRepository.findByOrderNo(any())).thenReturn(Optional.empty());

            service.initiate(ORDER_NO, USER_ID);

            ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
            verify(paymentRepository).save(captor.capture());
            assertThat(captor.getValue().amount()).isEqualByComparingTo(AMOUNT);
        }

        @Test
        @DisplayName("重複發起沿用既有付款單——連點兩次不該產生兩張單")
        void reusesExistingPayment() {
            givenOrder(OrderStatus.PENDING_PAYMENT);
            when(paymentRepository.findByOrderNo(any())).thenReturn(Optional.of(pendingPayment()));

            service.initiate(ORDER_NO, USER_ID);

            verify(paymentNoGenerator, never()).next();
        }

        @Test
        @DisplayName("已收款的訂單：拒絕再次付款")
        void rejectsAlreadyPaidOrder() {
            givenOrder(OrderStatus.PENDING_PAYMENT);
            when(paymentRepository.findByOrderNo(any())).thenReturn(Optional.of(succeededPayment()));

            assertThatThrownBy(() -> service.initiate(ORDER_NO, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ORDER_NOT_PAYABLE);
        }

        @Test
        @DisplayName("已取消的訂單：不可發起付款")
        void rejectsCancelledOrder() {
            givenOrder(OrderStatus.CANCELLED);

            assertThatThrownBy(() -> service.initiate(ORDER_NO, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ORDER_NOT_PAYABLE);
        }

        @Test
        @DisplayName("別人的訂單：回「訂單不存在」而非「無權限」，避免訂單號枚舉")
        void hidesOthersOrders() {
            givenOrder(OrderStatus.PENDING_PAYMENT);

            assertThatThrownBy(() -> service.initiate(ORDER_NO, 999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("閘道回調")
    class Callback {

        @Test
        @DisplayName("簽章驗證失敗：拒絕，且不碰任何資料")
        void rejectsInvalidSignature() {
            when(paymentGateway.verifyCallbackSignature(anyMap())).thenReturn(false);

            assertThatThrownBy(() -> service.handleGatewayCallback(successCallback()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_CALLBACK_SIGNATURE);

            // 這是最重要的一條：驗簽必須在任何查詢之前
            verify(paymentRepository, never()).findByPaymentNo(any());
        }

        @Test
        @DisplayName("付款成功且訂單仍待付款：訂單轉為已付款")
        void settlesOrderOnSuccess() {
            Payment payment = pendingPayment();
            when(paymentRepository.findByPaymentNo(any())).thenReturn(Optional.of(payment));
            SeckillOrder order = order(OrderStatus.PENDING_PAYMENT);
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order));

            service.handleGatewayCallback(successCallback());

            assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(order.status()).isEqualTo(OrderStatus.PAID);
            verify(orderRepository).update(order);
        }

        @Test
        @DisplayName("重複回調：略過，不重複處理——閘道會重送，這是常態")
        void ignoresDuplicateCallback() {
            when(paymentRepository.findByPaymentNo(any())).thenReturn(Optional.of(succeededPayment()));

            service.handleGatewayCallback(successCallback());

            verify(orderRepository, never()).update(any());
            verify(metrics).recordCallback("duplicate");
        }

        @Test
        @DisplayName("付款失敗：標記失敗，訂單不動")
        void marksFailure() {
            Payment payment = pendingPayment();
            when(paymentRepository.findByPaymentNo(any())).thenReturn(Optional.of(payment));

            Map<String, String> callback = new HashMap<>(successCallback());
            callback.put("result", "FAILED");
            callback.put("failureReason", "餘額不足");
            service.handleGatewayCallback(callback);

            assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.failureReason()).isEqualTo("餘額不足");
            verify(orderRepository, never()).update(any());
        }
    }

    @Nested
    @DisplayName("競態：錢收了但訂單入不了帳")
    class PaymentAfterOrderClosed {

        /**
         * 這是本類別存在的理由。
         *
         * <p>時序：使用者跳轉金流頁面 → 逾時關單排程取消訂單並退回庫存 →
         * 使用者完成付款 → 回調抵達。此時錢已經收了，訂單卻已是終態。
         */
        @Test
        @DisplayName("訂單已取消：付款仍記為成功，並轉為待退款")
        void recordsSuccessThenMarksRefundRequired() {
            Payment payment = pendingPayment();
            when(paymentRepository.findByPaymentNo(any())).thenReturn(Optional.of(payment));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order(OrderStatus.CANCELLED)));

            service.handleGatewayCallback(successCallback());

            // 錢真的收了，絕不能標記為失敗——那會讓帳目與現實脫節
            assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_PENDING);
            assertThat(payment.status().moneyReceived()).isTrue();
            assertThat(payment.gatewayTransactionId()).isEqualTo(TXN_ID);
        }

        @Test
        @DisplayName("絕不強制把已取消的訂單改回已付款——庫存已退回，那會製造超賣")
        void neverResurrectsCancelledOrder() {
            when(paymentRepository.findByPaymentNo(any())).thenReturn(Optional.of(pendingPayment()));
            SeckillOrder cancelled = order(OrderStatus.CANCELLED);
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(cancelled));

            service.handleGatewayCallback(successCallback());

            assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
            verify(orderRepository, never()).update(any());
        }

        /**
         * 兩個事件都要發，順序也有意義。
         *
         * <p>只發退款事件會讓帳本上的退款看起來憑空發生——
         * 下游的財務系統會看到一筆沒有對應收入的支出。
         * 錢確實進來過，就必須如實記錄，再記錄它出去。
         */
        @Test
        @DisplayName("同時發出收款成功與退款事件——帳本必須記錄錢進來也記錄錢出去")
        void emitsBothSucceededAndRefundRequired() {
            when(paymentRepository.findByPaymentNo(any())).thenReturn(Optional.of(pendingPayment()));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order(OrderStatus.CANCELLED)));

            service.handleGatewayCallback(successCallback());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DomainEvent>> captor = ArgumentCaptor.forClass(List.class);
            verify(eventOutbox).append(captor.capture());

            assertThat(captor.getValue())
                    .hasSize(2)
                    .satisfiesExactly(
                            first -> assertThat(first).isInstanceOf(PaymentSucceededEvent.class),
                            second -> assertThat(second).isInstanceOf(PaymentRefundRequiredEvent.class));
        }

        @Test
        @DisplayName("訂單根本不存在也走同一條路——錢收了就必須退")
        void handlesMissingOrder() {
            Payment payment = pendingPayment();
            when(paymentRepository.findByPaymentNo(any())).thenReturn(Optional.of(payment));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.empty());

            service.handleGatewayCallback(successCallback());

            assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_PENDING);
        }
    }

    // ---- fixtures ----

    private void givenOrder(OrderStatus status) {
        when(orderRepository.findByOrderNo(OrderNo.of(ORDER_NO))).thenReturn(Optional.of(order(status)));
    }

    private static SeckillOrder order(OrderStatus status) {
        return SeckillOrder.restore(OrderNo.of(ORDER_NO), 1001L, USER_ID, "req-1",
                1, AMOUNT, status, NOW.minusSeconds(600), null, null, 0L);
    }

    private static Payment pendingPayment() {
        return Payment.restore(1L, PaymentNo.of(PAYMENT_NO), OrderNo.of(ORDER_NO), USER_ID,
                AMOUNT, PaymentStatus.PENDING, null, NOW.minusSeconds(60), null, null, 0L);
    }

    private static Payment succeededPayment() {
        return Payment.restore(1L, PaymentNo.of(PAYMENT_NO), OrderNo.of(ORDER_NO), USER_ID,
                AMOUNT, PaymentStatus.SUCCEEDED, TXN_ID, NOW.minusSeconds(60), NOW, null, 0L);
    }

    private static Map<String, String> successCallback() {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("paymentNo", PAYMENT_NO);
        parameters.put("result", "SUCCESS");
        parameters.put("transactionId", TXN_ID);
        parameters.put("signature", "valid-signature");
        return parameters;
    }
}
