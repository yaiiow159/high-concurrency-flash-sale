package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.out.PaymentGateway;
import com.flashsale.application.port.out.PaymentMetrics;
import com.flashsale.application.port.out.PaymentRepository;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.payment.Payment;
import com.flashsale.domain.payment.PaymentNo;
import com.flashsale.domain.payment.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 待退款的落庫。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("待退款處理")
class PaymentRefunderTest {

    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentGateway paymentGateway;
    @Mock private PaymentMetrics metrics;

    private PaymentRefundScheduler.PaymentRefunder refunder;

    @BeforeEach
    void setUp() {
        refunder = new PaymentRefundScheduler.PaymentRefunder(
                paymentRepository, paymentGateway, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Payment refundPending() {
        Payment payment = Payment.initiate(PaymentNo.of("PAY-222647798879748096"),
                OrderNo.of("222647798879748000"), 88L, new BigDecimal("990"), NOW);
        payment.markSucceeded("GW-1", NOW);
        payment.markRefundRequired("訂單已關閉", NOW);
        payment.pullDomainEvents();
        return payment;
    }

    @Test
    @DisplayName("閘道成功才落庫，且一筆一次交易——整批包一個交易的話，"
            + "第 50 筆的例外會把前 49 筆的「已退款」一起回滾，而那些錢已經出去了")
    void savesPerPayment() {
        Payment payment = refundPending();
        when(paymentGateway.refund(any(), any(), anyString()))
                .thenReturn(PaymentGateway.RefundOutcome.success("GW-REFUND-1"));

        assertThat(refunder.refundOne(payment)).isTrue();

        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
        verify(paymentRepository).save(payment);
    }

    @Test
    @DisplayName("閘道回失敗：不落庫、不拋出，下一輪再試")
    void keepsPendingWhenGatewayRefuses() {
        Payment payment = refundPending();
        when(paymentGateway.refund(any(), any(), anyString()))
                .thenReturn(PaymentGateway.RefundOutcome.failure("閘道逾時"));

        assertThat(refunder.refundOne(payment)).isFalse();

        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_PENDING);
        verify(paymentRepository, never()).save(any());
        verify(metrics).recordRefund(false);
    }

    @Test
    @DisplayName("例外不逸出：一筆退不掉不該讓其他人的錢也卡著")
    void swallowsExceptionSoTheBatchContinues() {
        Payment payment = refundPending();
        when(paymentGateway.refund(any(), any(), anyString()))
                .thenThrow(new IllegalStateException("連線中斷"));

        assertThat(refunder.refundOne(payment)).isFalse();
    }
}
