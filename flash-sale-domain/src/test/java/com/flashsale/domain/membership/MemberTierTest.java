package com.flashsale.domain.membership;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 會員等級與積分回饋。
 *
 * <p>這裡釘住的是<b>激勵方向</b>：等級由累計消費決定而不是積分餘額，
 * 因為用餘額算會讓「花積分」變成「降級」——而花積分正是我們希望使用者做的事。
 * 這條規則寫錯不會拋任何例外，只會讓整個機制安靜地反過來。
 */
@DisplayName("會員等級")
class MemberTierTest {

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    @Nested
    @DisplayName("等級判定")
    class Thresholds {

        @Test
        @DisplayName("剛好達到門檻就算升級——差一元不算是使用者最容易抱怨的邊界")
        void thresholdIsInclusive() {
            assertThat(MemberTier.forSpend(money("9999"))).isEqualTo(MemberTier.BRONZE);
            assertThat(MemberTier.forSpend(money("10000"))).isEqualTo(MemberTier.SILVER);
            assertThat(MemberTier.forSpend(money("49999"))).isEqualTo(MemberTier.SILVER);
            assertThat(MemberTier.forSpend(money("50000"))).isEqualTo(MemberTier.GOLD);
            assertThat(MemberTier.forSpend(money("200000"))).isEqualTo(MemberTier.PLATINUM);
        }

        @Test
        @DisplayName("零消費與 null 都是一般會員，不是崩潰")
        void emptySpendIsBronze() {
            assertThat(MemberTier.forSpend(BigDecimal.ZERO)).isEqualTo(MemberTier.BRONZE);
            assertThat(MemberTier.forSpend(null)).isEqualTo(MemberTier.BRONZE);
        }

        @Test
        @DisplayName("超過最高門檻很多仍然是最高級，不會溢出到下一個列舉")
        void aboveTopStaysTop() {
            assertThat(MemberTier.forSpend(money("99999999"))).isEqualTo(MemberTier.PLATINUM);
        }
    }

    @Nested
    @DisplayName("積分回饋")
    class Points {

        @Test
        @DisplayName("每 100 元 1 點，並依等級加倍")
        void multiplierApplies() {
            assertThat(MemberTier.BRONZE.pointsFor(money("1000"))).isEqualTo(10);
            assertThat(MemberTier.SILVER.pointsFor(money("1000"))).isEqualTo(12);
            assertThat(MemberTier.GOLD.pointsFor(money("1000"))).isEqualTo(15);
            assertThat(MemberTier.PLATINUM.pointsFor(money("1000"))).isEqualTo(20);
        }

        @Test
        @DisplayName("無條件捨去——四捨五入會讓「消費 50 元拿 1 點」這種像 bug 的結果出現")
        void roundsDown() {
            assertThat(MemberTier.BRONZE.pointsFor(money("50"))).isZero();
            assertThat(MemberTier.BRONZE.pointsFor(money("199"))).isEqualTo(1);
            // 1.2 × 199 / 100 = 2.388 → 2
            assertThat(MemberTier.SILVER.pointsFor(money("199"))).isEqualTo(2);
        }

        @Test
        @DisplayName("零元與負數回 0，不是負積分")
        void nonPositiveEarnsNothing() {
            assertThat(MemberTier.BRONZE.pointsFor(BigDecimal.ZERO)).isZero();
            assertThat(MemberTier.BRONZE.pointsFor(money("-100"))).isZero();
            assertThat(MemberTier.BRONZE.pointsFor(null)).isZero();
        }

        @Test
        @DisplayName("高等級一定拿得比低等級多——倍率若寫反，這條會紅")
        void higherTierAlwaysEarnsMore() {
            BigDecimal amount = money("10000");
            long previous = -1;
            for (MemberTier tier : MemberTier.values()) {
                long earned = tier.pointsFor(amount);
                assertThat(earned).isGreaterThan(previous);
                previous = earned;
            }
        }
    }

    @Nested
    @DisplayName("升級進度")
    class Progress {

        @Test
        @DisplayName("距離下一級的差額由後端算，不讓前端減出負數")
        void gapNeverNegative() {
            assertThat(MemberTier.BRONZE.amountToNextTier(money("3000")))
                    .isEqualByComparingTo("7000");
            // 已經超過門檻但還沒被重算等級時，差額是 0 而不是負數
            assertThat(MemberTier.BRONZE.amountToNextTier(money("15000")))
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("最高級沒有下一級，差額為 0 且 next() 回自己")
        void topTierHasNoNext() {
            assertThat(MemberTier.PLATINUM.isHighest()).isTrue();
            assertThat(MemberTier.PLATINUM.next()).isEqualTo(MemberTier.PLATINUM);
            assertThat(MemberTier.PLATINUM.amountToNextTier(money("500000")))
                    .isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("帳戶")
    class Account {

        @Test
        @DisplayName("等級從累計消費當場推導，不讀存下來的欄位——門檻調整要立刻生效")
        void tierIsDerived() {
            MemberAccount account = new MemberAccount(1L, 0L, money("60000"));

            assertThat(account.tier()).isEqualTo(MemberTier.GOLD);
        }

        @Test
        @DisplayName("進度條夾在 0–100，剛升級與已達頂級都不會算出離譜的值")
        void progressIsClamped() {
            assertThat(new MemberAccount(1L, 0L, money("10000")).progressToNextTier()).isZero();
            assertThat(new MemberAccount(1L, 0L, money("30000")).progressToNextTier()).isEqualTo(50);
            assertThat(new MemberAccount(1L, 0L, money("999999")).progressToNextTier())
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("負餘額是真實的債務，不是錯誤狀態")
        void negativeBalanceIsAllowed() {
            MemberAccount indebted = new MemberAccount(1L, -50L, money("1000"));

            assertThat(indebted.isInDebt()).isTrue();
            assertThat(indebted.pointBalance()).isEqualTo(-50);
        }

        @Test
        @DisplayName("新會員拿得到一個完整的帳戶，不是 null")
        void freshAccountIsUsable() {
            MemberAccount fresh = MemberAccount.fresh(1L);

            assertThat(fresh.tier()).isEqualTo(MemberTier.BRONZE);
            assertThat(fresh.pointBalance()).isZero();
            assertThat(fresh.progressToNextTier()).isZero();
        }
    }
}
