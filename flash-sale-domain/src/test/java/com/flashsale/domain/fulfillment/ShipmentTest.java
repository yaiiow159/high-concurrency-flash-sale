package com.flashsale.domain.fulfillment;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("出貨單")
class ShipmentTest {

    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(86400);
    private static final long USER = 88L;
    private static final String ORDER_NO = "220956648921890816";

    @Nested
    @DisplayName("配送失敗可以重送")
    class RedispatchAfterFailure {

        @Test
        @DisplayName("失敗不是終態——現實中的後續幾乎都是重新派送")
        void failureIsNotTerminal() {
            Shipment shipment = inTransit();

            shipment.markFailed("收件人不在");

            assertThat(shipment.status()).isEqualTo(ShipmentStatus.FAILED);
            assertThat(shipment.status().isFinal()).isFalse();
            assertThatCode(() -> shipment.dispatch(Carrier.TCAT, "TC-002", LATER))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("重送會累加派送次數——那是物流品質的指標")
        void countsDispatches() {
            Shipment shipment = inTransit();
            shipment.markFailed("地址錯誤");
            shipment.dispatch(Carrier.TCAT, "TC-002", LATER);

            assertThat(shipment.dispatchCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("重送不覆寫首次出貨時間——那是出貨時效的分母")
        void keepsFirstShippedAt() {
            Shipment shipment = inTransit();
            shipment.markFailed("收件人不在");
            shipment.dispatch(Carrier.TCAT, "TC-002", LATER);

            assertThat(shipment.shippedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("重送會清掉上一次的失敗原因，不留下誤導的殘留")
        void clearsPreviousFailureReason() {
            Shipment shipment = inTransit();
            shipment.markFailed("收件人不在");
            shipment.dispatch(Carrier.TCAT, "TC-002", LATER);

            assertThat(shipment.failureReason()).isNull();
        }
    }

    @Nested
    @DisplayName("狀態機")
    class StateMachine {

        @Test
        @DisplayName("送達是終態，不可再變更")
        void deliveredIsFinal() {
            Shipment shipment = inTransit();
            shipment.deliver(LATER);

            assertThat(shipment.status().isFinal()).isTrue();
            assertThatThrownBy(() -> shipment.markFailed("不該發生"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ILLEGAL_SHIPMENT_STATE_TRANSITION);
        }

        @Test
        @DisplayName("還沒出貨就不能標記送達")
        void cannotDeliverBeforeDispatch() {
            assertThatThrownBy(() -> ready().deliver(NOW))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("已出貨就不能再取消——貨在路上，庫存不能直接退回可售池")
        void cannotCancelAfterDispatch() {
            Shipment shipment = inTransit();

            assertThat(shipment.isCancellable()).isFalse();
            assertThatThrownBy(() -> shipment.cancel("使用者取消"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("出貨前可以取消")
        void cancellableBeforeDispatch() {
            Shipment shipment = ready();

            assertThat(shipment.isCancellable()).isTrue();
            assertThatCode(() -> shipment.cancel("訂單取消")).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("物流單號")
    class Tracking {

        @Test
        @DisplayName("沒有單號的出貨等於無法追蹤，一律拒絕")
        void requiresTrackingNumber() {
            assertThatThrownBy(() -> ready().dispatch(Carrier.TCAT, " ", NOW))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> ready().dispatch(Carrier.TCAT, null, NOW))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("承運商決定查詢網址；沒有外部系統的回 null 而非假連結")
        void trackingUrlComesFromCarrier() {
            assertThat(Carrier.TCAT.trackingUrl("TC-001")).endsWith("TC-001");
            assertThat(Carrier.SELF.trackingUrl("X-1"))
                    .as("自行配送沒有外部查詢系統")
                    .isNull();
            assertThat(Carrier.CVS.trackingUrl("X-1")).isNull();
        }
    }

    @Nested
    @DisplayName("擁有者")
    class Ownership {

        @Test
        @DisplayName("不是自己的出貨單一律當成不存在")
        void foreignShipmentLooksMissing() {
            assertThatThrownBy(() -> ready().requireOwnedBy(999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.SHIPMENT_NOT_FOUND);
        }
    }

    // ---- fixtures ----

    private static Shipment ready() {
        return Shipment.restore(1L, ShipmentNo.of("SHP-1"), ORDER_NO, USER,
                null, null, ShipmentStatus.READY, null, 0, NOW, null, null);
    }

    private static Shipment inTransit() {
        Shipment shipment = ready();
        shipment.dispatch(Carrier.TCAT, "TC-001", NOW);
        return shipment;
    }
}
