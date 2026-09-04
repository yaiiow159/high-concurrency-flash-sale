package com.flashsale.domain.promotion;

import com.flashsale.domain.shared.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 優惠計算引擎。
 *
 * <h2>這裡窮舉的是「順序」與「分攤」</h2>
 *
 * <p>兩者都是<b>寫下來才存在</b>的規則：先打八折再減 100 與反過來差 20 元，
 * 而分攤的餘數給誰決定了退款時哪一行會差一分錢。
 * 沒有測試釘住的話，每個經手的人都會用自己的直覺，
 * 而那些直覺加總起來就是對不平的帳。
 */
@DisplayName("優惠計算引擎")
class PricingEngineTest {

    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");
    private static final Instant START = NOW.minusSeconds(3600);
    private static final Instant END = NOW.plusSeconds(3600);

    private static PricedItem line(String unitPrice, int quantity) {
        return new PricedItem(1L, new BigDecimal(unitPrice), quantity, null);
    }

    private static PricedItem seckillLine(String unitPrice, int quantity) {
        return new PricedItem(2L, new BigDecimal(unitPrice), quantity, 1001L);
    }

    private static Promotion fixed(long id, String name, DiscountType type,
                                   String threshold, String value) {
        return Promotion.of(id, name, type, PromotionRule.FIXED_AMOUNT,
                new BigDecimal(threshold), new BigDecimal(value), null, START, END, true);
    }

    private static Promotion percentage(long id, String name, DiscountType type,
                                        String value, String cap) {
        return Promotion.of(id, name, type, PromotionRule.PERCENTAGE,
                BigDecimal.ZERO, new BigDecimal(value), new BigDecimal(cap), START, END, true);
    }

    @Nested
    @DisplayName("計算順序")
    class Ordering {

        @Test
        @DisplayName("訂單折扣先於券——讓券先算會壓低小計，等於用券懲罰使用者")
        void orderDiscountBeforeCoupon() {
            // 小計 1000：滿 1000 折 100 → 900；券折 20% 上限 999 → 900 × 0.2 = 180 → 720
            List<PricedItem> lines = List.of(line("1000", 1));
            List<Promotion> promotions = List.of(
                    percentage(2L, "八折券", DiscountType.COUPON, "0.2", "999"),
                    fixed(1L, "滿千折百", DiscountType.ORDER_DISCOUNT, "1000", "100"));

            var result = PricingEngine.calculate(lines, promotions, NOW);

            assertThat(result.payable()).isEqualByComparingTo("720.00");
            // 若順序反過來（券先算）：1000×0.8=800，未達 1000 門檻，滿減不適用 → 800。
            // 使用者反而因為用了券而少折 80
            assertThat(result.discounts())
                    .extracting(AppliedDiscount::type)
                    .containsExactly(DiscountType.ORDER_DISCOUNT, DiscountType.COUPON);
        }

        @Test
        @DisplayName("每一級都對「上一級之後的金額」計算，這正是順序會改變結果的原因")
        void eachStageAppliesToTheRunningTotal() {
            List<PricedItem> lines = List.of(line("1000", 1));
            List<Promotion> promotions = List.of(
                    percentage(1L, "單品九折", DiscountType.ITEM_DISCOUNT, "0.1", "999"),
                    percentage(2L, "整單九折", DiscountType.ORDER_DISCOUNT, "0.1", "999"));

            var result = PricingEngine.calculate(lines, promotions, NOW);

            // 1000 → 折 100 → 900 → 折 90 → 810。若都對原價算就會是 800
            assertThat(result.payable()).isEqualByComparingTo("810.00");
        }
    }

    @Nested
    @DisplayName("秒殺不與任何優惠疊加")
    class SeckillExclusion {

        @Test
        @DisplayName("含秒殺行的訂單完全不套用優惠")
        void seckillLineGetsNoDiscount() {
            List<PricedItem> lines = List.of(seckillLine("100", 1));
            List<Promotion> promotions = List.of(
                    fixed(1L, "滿百折五十", DiscountType.ORDER_DISCOUNT, "100", "50"));

            var result = PricingEngine.calculate(lines, promotions, NOW);

            assertThat(result.payable()).isEqualByComparingTo("100");
            assertThat(result.discounts()).isEmpty();
        }

        @Test
        @DisplayName("判準是 sourceActivityId 而不是通道——那是「價格從哪來」的精確事實")
        void judgedByLineOriginNotChannel() {
            // 同一張訂單混了一般行與秒殺行：整張都不套優惠。
            // 部分套用會需要把折扣分攤到「非秒殺的那幾行」，
            // 而那讓分攤規則多一個特例，特例正是優惠系統腐爛的起點
            List<PricedItem> mixed = List.of(line("1000", 1), seckillLine("100", 1));

            var result = PricingEngine.calculate(mixed,
                    List.of(fixed(1L, "滿千折百", DiscountType.ORDER_DISCOUNT, "1000", "100")), NOW);

            assertThat(result.discounts()).isEmpty();
        }
    }

    @Nested
    @DisplayName("分攤與進位")
    class Allocation {

        @Test
        @DisplayName("分攤加總必然等於折後總額——差一分錢，退完最後一行就對不平")
        void allocationsSumToPayable() {
            // 三行各 333.33，打九折後除不盡
            List<PricedItem> lines = List.of(line("333.33", 1), line("333.33", 1), line("333.33", 1));

            var result = PricingEngine.calculate(lines,
                    List.of(percentage(1L, "九折", DiscountType.ORDER_DISCOUNT, "0.1", "999")), NOW);

            BigDecimal summed = result.lineAllocations().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(summed).isEqualByComparingTo(result.payable());
        }

        @Test
        @DisplayName("餘數加到金額最大的那一行")
        void remainderGoesToLargestLine() {
            // 小計 1000，折 1 元 → 999 要分攤到 700 / 200 / 100 三行
            List<PricedItem> lines = List.of(line("700", 1), line("200", 1), line("100", 1));

            var result = PricingEngine.calculate(lines,
                    List.of(fixed(1L, "折一元", DiscountType.ORDER_DISCOUNT, "0", "1")), NOW);

            BigDecimal summed = result.lineAllocations().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(summed).isEqualByComparingTo("999.00");
            // 第一行最大，任何餘數都落在它身上
            assertThat(result.lineAllocations().get(0))
                    .isGreaterThanOrEqualTo(new BigDecimal("699.00"));
        }

        @Test
        @DisplayName("沒有折扣時分攤就是各行原本的小計")
        void noDiscountAllocatesOriginalSubtotals() {
            List<PricedItem> lines = List.of(line("300", 1), line("700", 1));

            var result = PricingEngine.calculate(lines, List.of(), NOW);

            assertThat(result.lineAllocations())
                    .containsExactly(new BigDecimal("300.00"), new BigDecimal("700.00"));
        }
    }

    @Nested
    @DisplayName("邊界")
    class Boundaries {

        @Test
        @DisplayName("折扣不可超過訂單金額——負數金額會一路流進付款與退款")
        void discountCappedAtOrderTotal() {
            List<PricedItem> lines = List.of(line("50", 1));

            var result = PricingEngine.calculate(lines,
                    List.of(fixed(1L, "折一百", DiscountType.ORDER_DISCOUNT, "0", "100")), NOW);

            assertThat(result.payable()).isEqualByComparingTo("0.00");
            assertThat(result.totalDiscount()).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("未達門檻的優惠不套用，而且是回 0 不是拋例外")
        void belowThresholdIsNotAnError() {
            List<PricedItem> lines = List.of(line("500", 1));

            var result = PricingEngine.calculate(lines,
                    List.of(fixed(1L, "滿千折百", DiscountType.ORDER_DISCOUNT, "1000", "100")), NOW);

            assertThat(result.discounts()).isEmpty();
            assertThat(result.payable()).isEqualByComparingTo("500");
        }

        @Test
        @DisplayName("過期與未開始的優惠不套用")
        void outsideWindowIsNotApplied() {
            Promotion expired = Promotion.of(1L, "已過期", DiscountType.ORDER_DISCOUNT,
                    PromotionRule.FIXED_AMOUNT, BigDecimal.ZERO, new BigDecimal("100"), null,
                    NOW.minusSeconds(7200), NOW.minusSeconds(3600), true);

            var result = PricingEngine.calculate(List.of(line("1000", 1)), List.of(expired), NOW);

            assertThat(result.discounts()).isEmpty();
        }

        @Test
        @DisplayName("結束時刻當下就不可用——endAt 是開區間")
        void endInstantIsExclusive() {
            Promotion promotion = fixed(1L, "滿百折十", DiscountType.ORDER_DISCOUNT, "0", "10");

            assertThat(promotion.isApplicableAt(END)).isFalse();
            assertThat(promotion.isApplicableAt(END.minusMillis(1))).isTrue();
        }

        @Test
        @DisplayName("停用的優惠不套用，即使還在有效期內")
        void disabledIsNotApplied() {
            Promotion disabled = Promotion.of(1L, "已停用", DiscountType.ORDER_DISCOUNT,
                    PromotionRule.FIXED_AMOUNT, BigDecimal.ZERO, new BigDecimal("100"), null,
                    START, END, false);

            var result = PricingEngine.calculate(List.of(line("1000", 1)), List.of(disabled), NOW);

            assertThat(result.discounts()).isEmpty();
        }
    }

    @Nested
    @DisplayName("優惠規則本身的約束")
    class PromotionInvariants {

        @Test
        @DisplayName("比例折扣沒有上限會被擋下——一張全站八折用在十萬訂單上就是折兩萬")
        void percentageMustHaveCap() {
            assertThatThrownBy(() -> Promotion.of(1L, "無上限八折", DiscountType.COUPON,
                    PromotionRule.PERCENTAGE, BigDecimal.ZERO, new BigDecimal("0.2"), null,
                    START, END, true))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("上限");
        }

        @Test
        @DisplayName("折扣率大於等於 1 會被擋下——那不是折扣，是送錢")
        void discountRateMustBeBelowOne() {
            assertThatThrownBy(() -> Promotion.of(1L, "十成折", DiscountType.COUPON,
                    PromotionRule.PERCENTAGE, BigDecimal.ZERO, BigDecimal.ONE,
                    new BigDecimal("100"), START, END, true))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("比例折抵夾在上限內")
        void percentageIsCapped() {
            Promotion capped = percentage(1L, "八折上限 100", DiscountType.COUPON, "0.2", "100");

            // 10000 × 0.2 = 2000，但上限 100
            assertThat(capped.discountFor(new BigDecimal("10000")))
                    .isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("折抵金額一律為正數——用負數表示折扣遲早會有人把符號弄反")
        void discountAmountMustBePositive() {
            assertThatThrownBy(() -> new AppliedDiscount(DiscountType.COUPON, 1L, "券",
                    new BigDecimal("-100")))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
