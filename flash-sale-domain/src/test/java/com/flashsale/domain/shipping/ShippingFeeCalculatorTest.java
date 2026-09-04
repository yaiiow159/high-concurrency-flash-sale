package com.flashsale.domain.shipping;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 運費計算。
 *
 * <p>運費是使用者在結帳頁<b>盯著看</b>的數字，算錯會直接變成客訴。
 * 而它錯的方式都很安靜：級距挑錯只是多收 30 元、
 * 離島判斷錯只是少收 100 元——兩者都不會拋任何例外。
 *
 * <p>因此這裡窮舉的是<b>邊界</b>：級距的上下緣、離島郵遞區號的頭尾、
 * 以及「查不到費率」這種必須報錯而不能猜的情況。
 */
@DisplayName("運費計算")
class ShippingFeeCalculatorTest {

    /** 費率表：本島三級、離島兩級。與遷移種下的資料一致。 */
    private static final List<ShippingRate> RATES = List.of(
            rate(ShippingMethod.HOME_DELIVERY, ShippingZone.MAIN_ISLAND, 5_000, "80"),
            rate(ShippingMethod.HOME_DELIVERY, ShippingZone.MAIN_ISLAND, 20_000, "120"),
            rate(ShippingMethod.HOME_DELIVERY, ShippingZone.MAIN_ISLAND, 50_000, "200"),
            rate(ShippingMethod.HOME_DELIVERY, ShippingZone.OUTLYING_ISLAND, 5_000, "200"),
            rate(ShippingMethod.HOME_DELIVERY, ShippingZone.OUTLYING_ISLAND, 20_000, "350"));

    private static ShippingRate rate(ShippingMethod method, ShippingZone zone,
                                     int maxWeight, String fee) {
        return new ShippingRate(method, zone, maxWeight, new BigDecimal(fee));
    }

    private static ShippingFeeCalculator.Result calculate(int grams, String postalCode) {
        return ShippingFeeCalculator.calculate(grams, postalCode,
                ShippingMethod.HOME_DELIVERY, RATES);
    }

    @Nested
    @DisplayName("級距")
    class Tiers {

        @Test
        @DisplayName("剛好等於上限算在該級距內——差一克跳一級是最容易被客訴的邊界")
        void upperBoundIsInclusive() {
            assertThat(calculate(5_000, "110").fee()).isEqualByComparingTo("80");
            assertThat(calculate(5_001, "110").fee()).isEqualByComparingTo("120");
        }

        @Test
        @DisplayName("取上限 >= 重量裡最小的那一筆，費率表的順序不影響結果")
        void picksSmallestSufficientTier() {
            // 把表反過來排，結果必須完全相同——
            // 靠順序的話，有人重新排一次費率表就會多收錢，而那不會拋任何例外
            List<ShippingRate> reversed = RATES.reversed();

            assertThat(ShippingFeeCalculator
                    .calculate(3_000, "110", ShippingMethod.HOME_DELIVERY, reversed).fee())
                    .isEqualByComparingTo("80");
        }

        @Test
        @DisplayName("套用的級距要回傳出來，客服才核對得了")
        void reportsAppliedTier() {
            assertThat(calculate(6_000, "110").appliedTier()).isEqualTo(20_000);
        }
    }

    @Nested
    @DisplayName("區域")
    class Zones {

        @Test
        @DisplayName("離島貴得多，而且要說得出是哪一區")
        void outlyingIslandCostsMore() {
            ShippingFeeCalculator.Result main = calculate(3_000, "110");
            ShippingFeeCalculator.Result outlying = calculate(3_000, "880");

            assertThat(main.fee()).isEqualByComparingTo("80");
            assertThat(outlying.fee()).isEqualByComparingTo("200");
            // 回傳區域是為了讓畫面能解釋「為什麼這一單比較貴」——
            // 一個沒有解釋的離島運費只會變成一通客服電話
            assertThat(outlying.zone()).isEqualTo(ShippingZone.OUTLYING_ISLAND);
        }

        @Test
        @DisplayName("離島區段的頭尾都算離島，相鄰的本島區號不算")
        void outlyingRangesAreExact() {
            // 澎湖 880–885
            assertThat(ShippingZone.fromPostalCode("879")).isEqualTo(ShippingZone.MAIN_ISLAND);
            assertThat(ShippingZone.fromPostalCode("880")).isEqualTo(ShippingZone.OUTLYING_ISLAND);
            assertThat(ShippingZone.fromPostalCode("885")).isEqualTo(ShippingZone.OUTLYING_ISLAND);
            assertThat(ShippingZone.fromPostalCode("886")).isEqualTo(ShippingZone.MAIN_ISLAND);
            // 馬祖 209–212
            assertThat(ShippingZone.fromPostalCode("208")).isEqualTo(ShippingZone.MAIN_ISLAND);
            assertThat(ShippingZone.fromPostalCode("209")).isEqualTo(ShippingZone.OUTLYING_ISLAND);
            assertThat(ShippingZone.fromPostalCode("212")).isEqualTo(ShippingZone.OUTLYING_ISLAND);
            assertThat(ShippingZone.fromPostalCode("213")).isEqualTo(ShippingZone.MAIN_ISLAND);
            // 綠島、蘭嶼
            assertThat(ShippingZone.fromPostalCode("951")).isEqualTo(ShippingZone.OUTLYING_ISLAND);
            assertThat(ShippingZone.fromPostalCode("952")).isEqualTo(ShippingZone.OUTLYING_ISLAND);
            assertThat(ShippingZone.fromPostalCode("950")).isEqualTo(ShippingZone.MAIN_ISLAND);
        }

        @Test
        @DisplayName("五碼郵遞區號只看前三碼")
        void onlyPrefixMatters() {
            assertThat(ShippingZone.fromPostalCode("88041")).isEqualTo(ShippingZone.OUTLYING_ISLAND);
            assertThat(ShippingZone.fromPostalCode("11049")).isEqualTo(ShippingZone.MAIN_ISLAND);
        }

        @Test
        @DisplayName("推導不到就當本島——方向對商家不利，但不會嚇跑使用者")
        void unknownPostalCodeFallsBackToMainIsland() {
            // 反過來（當成離島多收）會讓使用者在結帳頁看到一個他無法理解的金額
            assertThat(ShippingZone.fromPostalCode(null)).isEqualTo(ShippingZone.MAIN_ISLAND);
            assertThat(ShippingZone.fromPostalCode("")).isEqualTo(ShippingZone.MAIN_ISLAND);
            assertThat(ShippingZone.fromPostalCode("AB")).isEqualTo(ShippingZone.MAIN_ISLAND);
            assertThat(ShippingZone.fromPostalCode("XYZ12")).isEqualTo(ShippingZone.MAIN_ISLAND);
        }
    }

    @Nested
    @DisplayName("查不到費率時報錯，不猜")
    class MissingRates {

        @Test
        @DisplayName("超重超出所有級距要報錯——猜低了商家賠、猜高了使用者被多收")
        void overweightIsRejected() {
            assertThatThrownBy(() -> calculate(60_000, "110"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> assertThat(((BusinessException) thrown).errorCode())
                            .isEqualTo(ErrorCode.SHIPPING_RATE_NOT_FOUND));
        }

        @Test
        @DisplayName("尚未實作的配送方式查不到費率，而不是回 0")
        void unimplementedMethodIsRejected() {
            assertThatThrownBy(() -> ShippingFeeCalculator.calculate(
                    1_000, "110", ShippingMethod.CVS_PICKUP, RATES))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("離島的重量級距比本島少，超出時同樣報錯而不是退回本島費率")
        void outlyingHasItsOwnCeiling() {
            // 本島 50 公斤還有級距，離島只到 20 公斤。
            // 若實作退回「找不到就用本島」，這一條會綠——而那是少收一半運費
            assertThatThrownBy(() -> calculate(30_000, "880"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("重量")
    class Weight {

        @Test
        @DisplayName("總重量為 0 要報錯，不可當成免運")
        void zeroWeightIsRejected() {
            // 重量 0 只可能是所有 SKU 都沒填重量。
            // 當成免運會讓一個資料缺失變成一筆賠錢的訂單
            assertThatThrownBy(() -> calculate(0, "110"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("計費重量取實際與材積的大者——一箱衛生紙很輕但佔滿貨車")
        void chargeableWeightTakesTheLarger() {
            assertThat(ShippingFeeCalculator.chargeableWeight(1_000, 5_000)).isEqualTo(5_000);
            assertThat(ShippingFeeCalculator.chargeableWeight(8_000, 5_000)).isEqualTo(8_000);
        }
    }

    @Nested
    @DisplayName("費率本身的約束")
    class RateInvariants {

        @Test
        @DisplayName("負數運費會被擋下——那不是運費，是送錢")
        void negativeFeeIsRejected() {
            assertThatThrownBy(() -> rate(ShippingMethod.HOME_DELIVERY,
                    ShippingZone.MAIN_ISLAND, 5_000, "-10"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("零運費是合法的——那是免運級距")
        void zeroFeeIsAllowed() {
            assertThat(rate(ShippingMethod.HOME_DELIVERY, ShippingZone.MAIN_ISLAND, 5_000, "0")
                    .fee()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("重量上限必須為正")
        void weightCeilingMustBePositive() {
            assertThatThrownBy(() -> rate(ShippingMethod.HOME_DELIVERY,
                    ShippingZone.MAIN_ISLAND, 0, "80"))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
