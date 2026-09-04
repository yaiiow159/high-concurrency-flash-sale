package com.flashsale.domain.shipping;

import com.flashsale.domain.aftersales.ReturnLine;
import com.flashsale.domain.aftersales.ReturnNo;
import com.flashsale.domain.aftersales.ReturnReason;
import com.flashsale.domain.aftersales.ReturnRequest;
import com.flashsale.domain.order.OrderNo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退貨時運費退不退（ADR-0019 決策 7）。
 *
 * <p>{@code ReturnReason.isSellerFault()} 從一開始就存在，註解也寫著
 * 「決定運費由誰負擔」——但在運費做出來之前，<b>沒有任何地方用它算錢</b>。
 * 這組測試是那句註解第一次被兌現。
 *
 * <p>兩個條件是 AND，而它們各自漏掉的後果不同：
 * 漏掉「賣方責任」會讓每一筆退貨都退運費（商家吸收所有配送成本）；
 * 漏掉「全額退貨」會讓退一件商品就退掉整趟運費。
 */
@DisplayName("退貨時的運費")
class ShippingRefundRuleTest {

    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

    private static ReturnRequest request(ReturnReason reason) {
        return ReturnRequest.open(ReturnNo.of("RMA-221618540468240384"), OrderNo.of("220600000000000001"),
                42L, "req-1",
                List.of(ReturnLine.of(2001L, "商品", new BigDecimal("990"), 1)),
                reason, null, true, NOW);
    }

    @Test
    @DisplayName("賣方責任 + 全額退貨才退運費")
    void sellerFaultAndFullReturnRefundsShipping() {
        assertThat(request(ReturnReason.DEFECTIVE).shouldRefundShipping(true)).isTrue();
        assertThat(request(ReturnReason.NOT_AS_DESCRIBED).shouldRefundShipping(true)).isTrue();
        assertThat(request(ReturnReason.WRONG_ITEM).shouldRefundShipping(true)).isTrue();
    }

    @Test
    @DisplayName("買家改變心意不退運費——配送已經發生，那個成本是真的")
    void buyerRemorseDoesNotRefundShipping() {
        assertThat(request(ReturnReason.CHANGED_MIND).shouldRefundShipping(true)).isFalse();
        assertThat(request(ReturnReason.OTHER).shouldRefundShipping(true)).isFalse();
    }

    @Test
    @DisplayName("部分退貨不退運費，即使是賣方責任——貨還是寄了那一趟")
    void partialReturnDoesNotRefundShipping() {
        // 漏掉這個條件的話，退一件商品就退掉整趟運費
        assertThat(request(ReturnReason.DEFECTIVE).shouldRefundShipping(false)).isFalse();
    }

    @Test
    @DisplayName("兩個條件是 AND，缺一不可")
    void bothConditionsAreRequired() {
        assertThat(request(ReturnReason.CHANGED_MIND).shouldRefundShipping(false)).isFalse();
    }
}
