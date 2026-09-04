package com.flashsale.application.service;

import com.flashsale.application.port.out.MembershipRepository;
import com.flashsale.application.port.out.PromotionRepository;
import com.flashsale.domain.membership.MemberAccount;
import com.flashsale.domain.membership.PointReason;
import com.flashsale.domain.membership.PointTransaction;
import com.flashsale.domain.promotion.DiscountType;
import com.flashsale.domain.promotion.Promotion;
import com.flashsale.domain.promotion.PromotionRule;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 會員積分。
 *
 * <p>這裡盯的是<b>三條套利路徑</b>——那是這個功能與其他功能的差別：
 * 庫存與訂單的錯誤是「系統做錯了」，積分的錯誤是
 * 「使用者發現了一個可以重複做的動作」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("會員積分")
class MembershipServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long USER = 42L;
    private static final String ORDER_NO = "220600000000000001";
    private static final String RETURN_NO = "RMA-1";

    @Mock private MembershipRepository membershipRepository;
    @Mock private PromotionRepository promotionRepository;

    private MembershipService service() {
        return new MembershipService(membershipRepository, promotionRepository, CLOCK);
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    private void givenAccount(long balance, String spend) {
        when(membershipRepository.findAccount(USER))
                .thenReturn(new MemberAccount(USER, balance, money(spend)));
    }

    private void givenRecordSucceeds() {
        when(membershipRepository.record(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(true);
    }

    @Nested
    @DisplayName("入帳")
    class Award {

        @Test
        @DisplayName("倍率取自入帳當下的等級——升級的效果從下一單開始")
        void usesTierAtAwardTime() {
            // 累計 60000 = 金卡，倍率 1.5
            givenAccount(0L, "60000");
            givenRecordSucceeds();

            long points = service().awardForOrder(USER, ORDER_NO, money("1000"));

            assertThat(points).isEqualTo(15);
            verify(membershipRepository).record(eq(USER), eq(15L),
                    eq(PointReason.ORDER_COMPLETED), eq(ORDER_NO), eq(money("1000")), any());
        }

        @Test
        @DisplayName("重放不會入帳兩次——儲存庫回 false 代表唯一索引擋下了")
        void replayEarnsNothing() {
            givenAccount(0L, "0");
            when(membershipRepository.record(anyLong(), anyLong(), any(), any(), any(), any()))
                    .thenReturn(false);

            assertThat(service().awardForOrder(USER, ORDER_NO, money("1000"))).isZero();
        }

        @Test
        @DisplayName("金額不足一點時不寫流水——零異動的流水只會讓它變長而說不出任何事")
        void tooSmallEarnsNothing() {
            givenAccount(0L, "0");

            assertThat(service().awardForOrder(USER, ORDER_NO, money("50"))).isZero();

            verify(membershipRepository, never())
                    .record(anyLong(), anyLong(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("退貨扣回")
    class Clawback {

        private void givenEarned(long points) {
            when(membershipRepository.findByReference(USER, PointReason.ORDER_COMPLETED, ORDER_NO))
                    .thenReturn(Optional.of(new PointTransaction(1L, USER, points, points,
                            PointReason.ORDER_COMPLETED, ORDER_NO, NOW)));
        }

        @Test
        @DisplayName("全額退貨收回全部積分——不收回的話「買了再退」就是免費的積分")
        void fullRefundClawsBackEverything() {
            givenEarned(15);
            givenRecordSucceeds();

            long clawed = service().clawbackForReturn(USER, ORDER_NO, RETURN_NO,
                    money("1000"), money("1000"));

            assertThat(clawed).isEqualTo(15);
        }

        @Test
        @DisplayName("部分退貨按比例收回")
        void partialRefundIsProportional() {
            givenEarned(15);
            givenRecordSucceeds();

            // 退 400 / 1000 = 40%，15 × 0.4 = 6
            assertThat(service().clawbackForReturn(USER, ORDER_NO, RETURN_NO,
                    money("400"), money("1000"))).isEqualTo(6);
        }

        @Test
        @DisplayName("比例的基準是流水裡的原始入帳，不是用當下等級重算")
        void basisIsTheOriginalLedgerEntry() {
            // 當初以一般會員入帳 10 點；現在使用者已經是白金（倍率 2.0）
            givenEarned(10);
            givenAccount(0L, "500000");
            givenRecordSucceeds();

            long clawed = service().clawbackForReturn(USER, ORDER_NO, RETURN_NO,
                    money("1000"), money("1000"));

            // 重算的話會是 20——收回比當初給的還多
            assertThat(clawed).isEqualTo(10);
        }

        @Test
        @DisplayName("累計消費也要扣回，否則「買到升級再退貨」是可以無限重複的")
        void cumulativeSpendIsAlsoClawedBack() {
            givenEarned(15);
            givenRecordSucceeds();

            service().clawbackForReturn(USER, ORDER_NO, RETURN_NO, money("400"), money("1000"));

            ArgumentCaptor<BigDecimal> spend = ArgumentCaptor.forClass(BigDecimal.class);
            verify(membershipRepository).record(eq(USER), eq(-6L),
                    eq(PointReason.RETURN_CLAWBACK), eq(RETURN_NO), spend.capture(), any());
            assertThat(spend.getValue()).isEqualByComparingTo("-400");
        }

        @Test
        @DisplayName("沒入過帳就沒東西可扣——送達前退貨會走到這裡，那是正常路徑")
        void nothingToClawBackWhenNeverAwarded() {
            when(membershipRepository.findByReference(any(), any(), any()))
                    .thenReturn(Optional.empty());

            assertThat(service().clawbackForReturn(USER, ORDER_NO, RETURN_NO,
                    money("1000"), money("1000"))).isZero();

            verify(membershipRepository, never())
                    .record(anyLong(), anyLong(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("退款超過訂單金額時扣回夾在原始入帳，不會扣得比當初給的多")
        void clawbackIsCappedAtWhatWasGiven() {
            givenEarned(15);
            givenRecordSucceeds();

            assertThat(service().clawbackForReturn(USER, ORDER_NO, RETURN_NO,
                    money("5000"), money("1000"))).isEqualTo(15);
        }
    }

    @Nested
    @DisplayName("兌換優惠券")
    class Exchange {

        private Promotion exchangeable(long cost) {
            return Promotion.of(9L, "100 元折價券", DiscountType.COUPON,
                    PromotionRule.FIXED_AMOUNT, money("1000"), money("100"), null,
                    NOW.minusSeconds(3600), NOW.plusSeconds(3600), true, cost);
        }

        @Test
        @DisplayName("扣點成功才算兌換完成")
        void successfulExchange() {
            when(promotionRepository.findPromotionById(9L))
                    .thenReturn(Optional.of(exchangeable(100)));
            when(promotionRepository.issueCoupon(eq(USER), eq(9L), any())).thenReturn("EX-ABC");
            when(membershipRepository.redeem(eq(USER), eq(100L), eq("EX-ABC"), any()))
                    .thenReturn(true);
            givenAccount(400L, "0");

            var result = service().exchangeForCoupon(USER, 9L);

            assertThat(result.couponCode()).isEqualTo("EX-ABC");
            assertThat(result.pointsSpent()).isEqualTo(100);
        }

        @Test
        @DisplayName("點數不足要拋例外而不是回一個結果——發券已經執行，交易必須回滾")
        void insufficientPointsRollsBack() {
            when(promotionRepository.findPromotionById(9L))
                    .thenReturn(Optional.of(exchangeable(100)));
            when(promotionRepository.issueCoupon(eq(USER), eq(9L), any())).thenReturn("EX-ABC");
            // 條件式 UPDATE 影響 0 列 = 點數不足
            when(membershipRepository.redeem(any(), anyLong(), any(), any())).thenReturn(false);

            assertThatThrownBy(() -> service().exchangeForCoupon(USER, 9L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> assertThat(((BusinessException) thrown).errorCode())
                            .isEqualTo(ErrorCode.INSUFFICIENT_POINTS));
        }

        @Test
        @DisplayName("不開放兌換的優惠換不了——大多數優惠都是商家給的，不是換的")
        void nonExchangeableIsRejected() {
            Promotion notExchangeable = Promotion.of(9L, "滿千折百", DiscountType.ORDER_DISCOUNT,
                    PromotionRule.FIXED_AMOUNT, money("1000"), money("100"), null,
                    NOW.minusSeconds(3600), NOW.plusSeconds(3600), true);
            when(promotionRepository.findPromotionById(9L)).thenReturn(Optional.of(notExchangeable));

            assertThatThrownBy(() -> service().exchangeForCoupon(USER, 9L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> assertThat(((BusinessException) thrown).errorCode())
                            .isEqualTo(ErrorCode.PROMOTION_NOT_EXCHANGEABLE));

            verify(promotionRepository, never()).issueCoupon(any(), any(), any());
        }
    }
}
