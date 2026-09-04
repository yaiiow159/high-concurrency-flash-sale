package com.flashsale.domain.order;

import com.flashsale.domain.shared.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 有折扣的訂單，退款要按「當初收了多少」退。
 *
 * <h2>這裡釘住的是一個會安靜流出錢的缺陷</h2>
 *
 * <p>整單折扣折在<b>訂單</b>上，退貨卻是退<b>一行</b>。
 * 用 {@code unitPrice × quantity} 算退款，退的是使用者沒付過的錢。
 *
 * <p>而且付款金額的上限攔不住它：全額退貨會超過已付金額而被擋下，
 * 但<b>部分退貨不會</b>——那筆多退的錢仍在上限之內，靜靜地流出去。
 */
@DisplayName("退款分攤")
class OrderLineRefundAllocationTest {

    private static OrderLine line(String unitPrice, int quantity, String allocated) {
        return new OrderLine(1L, "商品", new BigDecimal(unitPrice), quantity, null,
                new BigDecimal(allocated));
    }

    @Nested
    @DisplayName("退款金額來自實付分攤，不是定價")
    class RefundsFromAllocation {

        @Test
        @DisplayName("有折扣時退的是實付，不是單價乘數量")
        void refundsAllocatedNotListPrice() {
            // 定價 20000（2 件 × 10000），整單折扣分攤下來實付 19000
            OrderLine discounted = line("10000", 2, "19000");

            assertThat(discounted.refundFor(0, 2)).isEqualByComparingTo("19000.00");
            // 用定價算會是 20000——多退 1000，而這筆多退在部分退貨時
            // 完全不會碰到付款金額的上限
            assertThat(discounted.subtotal()).isEqualByComparingTo("20000");
        }

        @Test
        @DisplayName("沒有折扣時實付就是小計，行為與過去完全相同")
        void undiscountedBehavesAsBefore() {
            OrderLine plain = new OrderLine(1L, "商品", new BigDecimal("990"), 3, null);

            assertThat(plain.allocatedAmount()).isEqualByComparingTo("2970");
            assertThat(plain.refundFor(0, 3)).isEqualByComparingTo("2970.00");
        }
    }

    @Nested
    @DisplayName("分次退貨的加總必須等於實付")
    class CumulativeAllocation {

        @Test
        @DisplayName("除不盡時逐次退的加總仍然一分不差")
        void repeatedPartialRefundsSumExactly() {
            // 3 件實付 100.00，每件 33.333...
            OrderLine line = line("40", 3, "100.00");

            BigDecimal first = line.refundFor(0, 1);
            BigDecimal second = line.refundFor(1, 1);
            BigDecimal third = line.refundFor(2, 1);

            // 逐次捨去會是 33.33 × 3 = 99.99，漏掉一分錢。
            // 用累計差額算則餘數自然落在最後一次
            assertThat(first).isEqualByComparingTo("33.33");
            assertThat(second).isEqualByComparingTo("33.33");
            assertThat(third).isEqualByComparingTo("33.34");
            assertThat(first.add(second).add(third)).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("一次退兩件與分兩次退一件，總額相同")
        void batchingDoesNotChangeTheTotal() {
            OrderLine line = line("40", 3, "100.00");

            BigDecimal atOnce = line.refundFor(0, 2);
            BigDecimal split = line.refundFor(0, 1).add(line.refundFor(1, 1));

            assertThat(atOnce).isEqualByComparingTo(split);
        }
    }

    @Nested
    @DisplayName("守門")
    class Guards {

        @Test
        @DisplayName("實付高於原始小計會被擋下——那個方向就是「退得比收的多」")
        void allocationAboveSubtotalIsRejected() {
            assertThatThrownBy(() -> line("100", 2, "250"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不可高於原始小計");
        }

        @Test
        @DisplayName("退超過這一行的件數會被擋下")
        void refundBeyondQuantityIsRejected() {
            OrderLine line = line("100", 2, "200");

            assertThatThrownBy(() -> line.refundFor(1, 2))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> line.refundFor(0, 0))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
