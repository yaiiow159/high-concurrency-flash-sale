package com.flashsale.domain.payment;

import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.payment.event.PaymentRefundRequiredEvent;
import com.flashsale.domain.payment.event.PaymentSucceededEvent;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.DomainEvent;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("付款聚合根")
class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("29900.00");
    private static final String TXN = "TXN-1";

    @Nested
    @DisplayName("狀態機")
    class StateMachine {

        @Test
        @DisplayName("收款成功：登記 PaymentSucceededEvent")
        void succeedsAndEmitsEvent() {
            Payment payment = pending();

            payment.markSucceeded(TXN, NOW);

            assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(payment.pullDomainEvents()).singleElement().isInstanceOf(PaymentSucceededEvent.class);
        }

        @Test
        @DisplayName("成功的付款必須帶閘道交易編號——否則出事時無從對帳")
        void requiresTransactionIdOnSuccess() {
            assertThatThrownBy(() -> pending().markSucceeded(null, NOW))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("重複標記成功：拒絕。這是回調冪等的基礎，不需要額外的去重表")
        void rejectsDoubleSuccess() {
            Payment payment = pending();
            payment.markSucceeded(TXN, NOW);

            assertThatThrownBy(() -> payment.markSucceeded(TXN, NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ILLEGAL_PAYMENT_STATE_TRANSITION);
        }

        @Test
        @DisplayName("失敗後可重試，沿用同一張付款單")
        void allowsRetryAfterFailure() {
            Payment payment = pending();
            payment.markFailed("餘額不足", NOW);

            payment.retry(NOW);

            assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
            // 重試時必須清掉上一次的失敗原因與交易編號，否則會誤導對帳
            assertThat(payment.failureReason()).isNull();
            assertThat(payment.gatewayTransactionId()).isNull();
        }

        @ParameterizedTest
        @EnumSource(value = PaymentStatus.class, names = {"SUCCEEDED", "REFUND_PENDING", "REFUNDED"})
        @DisplayName("錢已收到的狀態都要如實反映——絕不可被當成「沒收到」")
        void moneyReceivedStatesAreHonest(PaymentStatus status) {
            assertThat(status.moneyReceived()).isTrue();
        }

        @Test
        @DisplayName("PENDING 與 FAILED 不代表收到錢")
        void unpaidStatesDoNotClaimMoney() {
            assertThat(PaymentStatus.PENDING.moneyReceived()).isFalse();
            assertThat(PaymentStatus.FAILED.moneyReceived()).isFalse();
        }

        @Test
        @DisplayName("已退款是終態")
        void refundedIsTerminal() {
            assertThat(PaymentStatus.REFUNDED.canTransitionTo(PaymentStatus.PENDING)).isFalse();
            assertThat(PaymentStatus.REFUNDED.canTransitionTo(PaymentStatus.SUCCEEDED)).isFalse();
        }
    }

    @Nested
    @DisplayName("錢收了但訂單入不了帳")
    class RefundRequired {

        @Test
        @DisplayName("SUCCEEDED → REFUND_PENDING：錢仍記為已收到，並登記退款事件")
        void marksRefundRequiredWithoutDenyingPayment() {
            Payment payment = pending();
            payment.markSucceeded(TXN, NOW);
            payment.pullDomainEvents();

            payment.markRefundRequired("付款完成時訂單已為 CANCELLED", NOW);

            assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_PENDING);
            // 關鍵：錢仍然是收到的狀態，不可退回成「失敗」
            assertThat(payment.status().moneyReceived()).isTrue();
            assertThat(payment.status().requiresAttention()).isTrue();

            List<DomainEvent> events = payment.pullDomainEvents();
            assertThat(events).singleElement().isInstanceOfSatisfying(
                    PaymentRefundRequiredEvent.class,
                    event -> assertThat(event.amount()).isEqualByComparingTo(AMOUNT));
        }

        @Test
        @DisplayName("未收款的付款不可轉為待退款——沒收到錢就沒有東西可退")
        void cannotRefundUnpaidPayment() {
            assertThatThrownBy(() -> pending().markRefundRequired("任何理由", NOW))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("退款完成後轉為 REFUNDED")
        void completesRefund() {
            Payment payment = pending();
            payment.markSucceeded(TXN, NOW);
            payment.markRefundRequired("訂單已關閉", NOW);

            payment.markRefunded(NOW);

            assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(payment.status().requiresAttention()).isFalse();
        }
    }

    @Nested
    @DisplayName("不變條件")
    class Invariants {

        @Test
        @DisplayName("金額必須大於 0")
        void rejectsNonPositiveAmount() {
            assertThatThrownBy(() -> Payment.initiate(
                    PaymentNo.of("PAY-12345678"), OrderNo.of("20250601001"), 1L, BigDecimal.ZERO, NOW))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("付款單號必須帶 PAY- 前綴，避免與訂單號在日誌中混淆")
        void requiresPaymentPrefix() {
            assertThatThrownBy(() -> PaymentNo.of("20250601001"))
                    .isInstanceOf(BusinessException.class);
            assertThat(PaymentNo.fromId(220349960435007488L).value()).startsWith("PAY-");
        }

        @Test
        @DisplayName("尚未終結的付款才需要處理回調")
        void detectsSettledPayments() {
            Payment payment = pending();
            assertThat(payment.isAlreadySettled()).isFalse();

            payment.markSucceeded(TXN, NOW);
            assertThat(payment.isAlreadySettled()).isTrue();
        }
    }

    private static Payment pending() {
        return Payment.initiate(PaymentNo.of("PAY-220349960435007499"),
                OrderNo.of("220349960435007488"), 42L, AMOUNT, NOW);
    }
}
