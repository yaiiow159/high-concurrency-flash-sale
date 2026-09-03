package com.flashsale.domain.payment;

import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 累計退款的上限——防重複退款的第三層（ADR-0011 決策 6、7）。
 *
 * <p><b>這一層是唯一兩條退款路徑都會經過的地方。</b>
 * 前兩層（退貨單狀態機、訂單行累計數量）都在退貨的脈絡裡，
 * 而 {@code PaymentRefundScheduler} 的競態補償看不到退貨單。
 *
 * <p>重複退款與重複扣款的代價不對稱：扣兩次會被客訴，
 * 退兩次是直接虧損，沒有任何事後對帳能補救。
 */
@DisplayName("付款：累計退款上限")
class PaymentRefundTest {

    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");

    private static Payment paidPayment(String amount) {
        Payment payment = Payment.initiate(PaymentNo.of("PAY-220956648921890816"),
                OrderNo.of("220956648921890816"), 88L, new BigDecimal(amount), NOW);
        payment.markSucceeded("GW-TXN-001", NOW);
        payment.pullDomainEvents();
        return payment;
    }

    @Test
    @DisplayName("部分退款後狀態為 PARTIALLY_REFUNDED，而不是繼續宣稱收款成功")
    void partialRefundIsVisibleInStatus() {
        Payment payment = paidPayment("1000");

        payment.applyRefund(new BigDecimal("300"), NOW);

        assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.refundedAmount()).isEqualByComparingTo("300");
        assertThat(payment.refundableAmount()).isEqualByComparingTo("700");
        // 錢確實還是收到過的，這個判斷不能因為退了一部分就翻面
        assertThat(payment.status().moneyReceived()).isTrue();
    }

    @Test
    @DisplayName("多次部分退款會累加——自我轉移是刻意允許的")
    void partialRefundsAccumulate() {
        Payment payment = paidPayment("1000");

        payment.applyRefund(new BigDecimal("300"), NOW);
        payment.applyRefund(new BigDecimal("200"), NOW);

        assertThat(payment.refundedAmount()).isEqualByComparingTo("500");
        assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    @DisplayName("退到剛好等於已付金額才轉為 REFUNDED，狀態由金額推導而非呼叫端指定")
    void becomesFullyRefundedOnlyAtTheCeiling() {
        Payment payment = paidPayment("1000");

        payment.applyRefund(new BigDecimal("600"), NOW);
        assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);

        payment.applyRefund(new BigDecimal("400"), NOW);
        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.refundableAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("累計超過已付金額會被拒絕——這是最後一道，繞不過去")
    void refuseRefundBeyondWhatWasPaid() {
        Payment payment = paidPayment("1000");
        payment.applyRefund(new BigDecimal("800"), NOW);

        assertThatThrownBy(() -> payment.applyRefund(new BigDecimal("300"), NOW))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REFUND_AMOUNT_EXCEEDED);

        // 被拒絕之後金額不可有任何變動，否則失敗的呼叫也會侵蝕額度
        assertThat(payment.refundedAmount()).isEqualByComparingTo("800");
        assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    @DisplayName("全額退完之後不能再退——重複投遞的退款事件在這裡止步")
    void fullyRefundedIsTerminal() {
        Payment payment = paidPayment("1000");
        payment.applyRefund(new BigDecimal("1000"), NOW);

        assertThatThrownBy(() -> payment.applyRefund(new BigDecimal("1"), NOW))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REFUND_AMOUNT_EXCEEDED);
    }

    @Test
    @DisplayName("退款金額必須為正——0 或負數是呼叫端算錯了，不該安靜通過")
    void refuseNonPositiveAmounts() {
        Payment payment = paidPayment("1000");

        assertThatThrownBy(() -> payment.applyRefund(BigDecimal.ZERO, NOW))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
        assertThatThrownBy(() -> payment.applyRefund(new BigDecimal("-100"), NOW))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("競態補償那條路的全額退款也會把累計金額填滿")
    void schedulerPathAlsoConsumesTheCeiling() {
        Payment payment = paidPayment("1000");
        payment.markRefundRequired("訂單已被關閉", NOW);
        payment.pullDomainEvents();

        payment.markRefunded(NOW);

        assertThat(payment.refundedAmount()).isEqualByComparingTo("1000");
    }
}
