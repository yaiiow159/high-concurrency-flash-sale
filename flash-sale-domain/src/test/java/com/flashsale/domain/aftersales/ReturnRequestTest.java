package com.flashsale.domain.aftersales;

import com.flashsale.domain.aftersales.event.RefundRequestedEvent;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("退貨單")
class ReturnRequestTest {

    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(86400);
    private static final long USER = 88L;
    private static final OrderNo ORDER_NO = OrderNo.of("220956648921890816");
    private static final ReturnNo RETURN_NO = ReturnNo.of("RMA-220956648921890999");

    private static ReturnLine line(long skuId, String price, int quantity) {
        return ReturnLine.of(skuId, "測試商品-" + skuId, new BigDecimal(price), quantity);
    }

    private static ReturnRequest open(boolean requiresGoodsReturn, ReturnLine... lines) {
        return ReturnRequest.open(RETURN_NO, ORDER_NO, USER, List.of(lines),
                ReturnReason.CHANGED_MIND, null, requiresGoodsReturn, NOW);
    }

    @Nested
    @DisplayName("需寄回與免寄回是兩條不同的路")
    class GoodsReturnBranch {

        @Test
        @DisplayName("免寄回時可從已核准直接退款——貨從未離開倉庫，沒有東西可驗收")
        void skipsInspectionWhenNothingToReturn() {
            ReturnRequest request = open(false, line(1L, "990", 1));
            request.approve(null, NOW);

            assertThatCode(() -> request.markRefunded(LATER)).doesNotThrowAnyException();
            assertThat(request.status()).isEqualTo(ReturnStatus.REFUNDED);
        }

        @Test
        @DisplayName("需寄回時未驗收就退款會被擋下——那是貨還沒回來就把錢退掉")
        void refuseRefundBeforeInspection() {
            ReturnRequest request = open(true, line(1L, "990", 1));
            request.approve(null, NOW);

            assertThatThrownBy(() -> request.markRefunded(LATER))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.ILLEGAL_RETURN_STATE_TRANSITION);
        }

        @Test
        @DisplayName("免寄回的單不能驗收——沒有退回品，驗收時間會是假資料")
        void refuseInspectionWhenNothingWasReturned() {
            ReturnRequest request = open(false, line(1L, "990", 1));
            request.approve(null, NOW);

            assertThatThrownBy(() -> request.receive(Map.of(1L, true), LATER))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.ILLEGAL_RETURN_STATE_TRANSITION);
        }
    }

    @Nested
    @DisplayName("驗收")
    class Inspection {

        @Test
        @DisplayName("漏掉任何一行都會被拒絕——預設為可再售會把毀損品算成庫存")
        void refuseIncompleteDecisions() {
            ReturnRequest request = open(true, line(1L, "990", 1), line(2L, "500", 2));
            request.approve(null, NOW);

            assertThatThrownBy(() -> request.receive(Map.of(1L, true), LATER))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("2");
        }

        @Test
        @DisplayName("不可再售的品項不進回補清單")
        void damagedGoodsAreNotRestocked() {
            ReturnRequest request = open(true, line(1L, "990", 1), line(2L, "500", 2));
            request.approve(null, NOW);
            request.receive(Map.of(1L, true, 2L, false), LATER);

            assertThat(request.restockableLines())
                    .extracting(ReturnLine::skuId)
                    .containsExactly(1L);
        }

        @Test
        @DisplayName("免寄回時全數回補——貨從未離開倉庫，不存在毀損的可能")
        void everythingRestocksWhenNothingShipped() {
            ReturnRequest request = open(false, line(1L, "990", 1), line(2L, "500", 2));

            assertThat(request.restockableLines()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("狀態機")
    class StateMachine {

        @Test
        @DisplayName("貨收下後不可撤回——那會讓買家既沒錢也沒貨")
        void cannotCancelAfterGoodsReceived() {
            ReturnRequest request = open(true, line(1L, "990", 1));
            request.approve(null, NOW);
            request.receive(Map.of(1L, true), LATER);

            assertThatThrownBy(() -> request.cancel(LATER))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.ILLEGAL_RETURN_STATE_TRANSITION);
        }

        @Test
        @DisplayName("核准前後都可以撤回——貨還沒寄出，撤回不留殘局")
        void canCancelBeforeGoodsReceived() {
            ReturnRequest beforeReview = open(true, line(1L, "990", 1));
            assertThatCode(() -> beforeReview.cancel(NOW)).doesNotThrowAnyException();

            ReturnRequest afterApproval = open(true, line(1L, "990", 1));
            afterApproval.approve(null, NOW);
            assertThatCode(() -> afterApproval.cancel(LATER)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("駁回必須說明理由——駁回而不說原因會直接變成客訴")
        void rejectionRequiresReason() {
            ReturnRequest request = open(true, line(1L, "990", 1));

            assertThatThrownBy(() -> request.reject("  ", NOW))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
        }

        @Test
        @DisplayName("退款是終態，不能再退第二次")
        void refundIsTerminal() {
            ReturnRequest request = open(false, line(1L, "990", 1));
            request.approve(null, NOW);
            request.markRefunded(LATER);

            assertThatThrownBy(() -> request.markRefunded(LATER))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.ILLEGAL_RETURN_STATE_TRANSITION);
        }

        @Test
        @DisplayName("駁回與撤回會釋放退貨額度，否則被駁回一次就永遠不能再申請")
        void closedStatusesReleaseQuota() {
            assertThat(ReturnStatus.REJECTED.holdsReturnQuota()).isFalse();
            assertThat(ReturnStatus.CANCELLED.holdsReturnQuota()).isFalse();
        }

        @Test
        @DisplayName("審核中的單仍佔用額度，否則可以在審核期間開第二張單重複退")
        void inFlightStatusesHoldQuota() {
            assertThat(ReturnStatus.REQUESTED.holdsReturnQuota()).isTrue();
            assertThat(ReturnStatus.APPROVED.holdsReturnQuota()).isTrue();
            assertThat(ReturnStatus.RECEIVED.holdsReturnQuota()).isTrue();
            assertThat(ReturnStatus.REFUNDED.holdsReturnQuota()).isTrue();
        }
    }

    @Nested
    @DisplayName("金額與事件")
    class AmountAndEvent {

        @Test
        @DisplayName("退款金額由快照單價算出，不是重新查來的價格")
        void refundAmountComesFromSnapshot() {
            ReturnRequest request = open(true, line(1L, "990", 2), line(2L, "500", 1));

            assertThat(request.refundAmount()).isEqualByComparingTo("2480");
        }

        @Test
        @DisplayName("退款時發出事件，且事件自帶要回補的品項——消費端不必回頭讀退貨單")
        void refundEmitsSelfContainedEvent() {
            ReturnRequest request = open(true, line(1L, "990", 2), line(2L, "500", 1));
            request.approve(null, NOW);
            request.receive(Map.of(1L, true, 2L, false), LATER);
            request.markRefunded(LATER);

            assertThat(request.pullDomainEvents())
                    .singleElement()
                    .isInstanceOfSatisfying(RefundRequestedEvent.class, event -> {
                        assertThat(event.refundAmount()).isEqualByComparingTo("2480");
                        assertThat(event.restockLines())
                                .extracting(RefundRequestedEvent.RestockLine::skuId)
                                .containsExactly(1L);
                        // partition key 是訂單號而非退貨單號：
                        // 同一張訂單的多次退款必須排成一列處理
                        assertThat(event.aggregateId()).isEqualTo(ORDER_NO.value());
                    });
        }

        @Test
        @DisplayName("事件只會被取出一次，避免重複寫進 outbox")
        void eventsArePulledOnce() {
            ReturnRequest request = open(false, line(1L, "990", 1));
            request.approve(null, NOW);
            request.markRefunded(LATER);

            assertThat(request.pullDomainEvents()).hasSize(1);
            assertThat(request.pullDomainEvents()).isEmpty();
        }
    }

    @Nested
    @DisplayName("擁有者")
    class Ownership {

        @Test
        @DisplayName("只認自己的使用者")
        void belongsToOwnerOnly() {
            ReturnRequest request = open(true, line(1L, "990", 1));

            assertThat(request.belongsTo(USER)).isTrue();
            assertThat(request.belongsTo(USER + 1)).isFalse();
        }
    }
}
