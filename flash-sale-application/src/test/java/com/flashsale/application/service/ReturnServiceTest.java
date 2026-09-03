package com.flashsale.application.service;

import com.flashsale.application.port.in.command.OpenReturnCommand;
import com.flashsale.application.port.in.dto.ReturnRequestView;
import com.flashsale.application.port.in.dto.ReturnableView;
import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.PaymentRepository;
import com.flashsale.application.port.out.ReturnNoGenerator;
import com.flashsale.application.port.out.ReturnRequestRepository;
import com.flashsale.domain.aftersales.ReturnLine;
import com.flashsale.domain.aftersales.ReturnNo;
import com.flashsale.domain.aftersales.ReturnReason;
import com.flashsale.domain.aftersales.ReturnRequest;
import com.flashsale.domain.aftersales.ReturnStatus;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderChannel;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.OrderStatus;
import com.flashsale.domain.order.ShippingInfo;
import com.flashsale.domain.payment.Payment;
import com.flashsale.domain.payment.PaymentNo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退貨服務。
 *
 * <p>測試盯的是這一層自己負責的三件事：
 *
 * <ol>
 *   <li><b>可退數量的計算</b>——防重複退款的第二層。審核中的單也要佔額度，
 *       被駁回的單則要把額度還回去</li>
 *   <li><b>訂單狀態只在全額退完時才改</b>——部分退款不能終結訂單</li>
 *   <li><b>是否需要寄回由訂單狀態決定</b>，不由呼叫端指定</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("退貨服務")
class ReturnServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");
    private static final long USER = 88L;
    private static final String ORDER_NO = "220956648921890816";
    private static final String RETURN_NO = "RMA-220956648921890999";

    @Mock
    private ReturnRequestRepository returnRepository;
    @Mock
    private ReturnNoGenerator returnNoGenerator;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private EventOutbox eventOutbox;

    private ReturnService service;

    @BeforeEach
    void setUp() {
        service = new ReturnService(returnRepository, returnNoGenerator, orderRepository,
                paymentRepository, eventOutbox, Clock.fixed(NOW, ZoneOffset.UTC));
        when(returnNoGenerator.next()).thenReturn(ReturnNo.of(RETURN_NO));
        when(returnRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(returnRepository.update(any())).thenAnswer(call -> call.getArgument(0));
        when(returnRepository.findByOrderNo(ORDER_NO)).thenReturn(List.of());
        when(returnRepository.findByRequestId(any())).thenReturn(Optional.empty());
    }

    /** 兩行：SKU 1 買 2 件 @990、SKU 2 買 1 件 @500，總額 2480。 */
    private static Order order(OrderStatus status) {
        return Order.restore(OrderNo.of(ORDER_NO), USER, OrderChannel.NORMAL, "req-1",
                List.of(new OrderLine(1L, "商品一", new BigDecimal("990"), 2, null),
                        new OrderLine(2L, "商品二", new BigDecimal("500"), 1, null)),
                new BigDecimal("2480"),
                new ShippingInfo("收件人", "0912345678", "100", "台北市", "中正區", "重慶南路一段"),
                status, NOW, NOW, null, 0L);
    }

    private static ReturnRequest existingReturn(ReturnStatus status, long skuId, int quantity) {
        return ReturnRequest.restore(1L, ReturnNo.of("RMA-220956648921890111"),
                OrderNo.of(ORDER_NO), USER, "req-existing", ReturnReason.CHANGED_MIND, null, true,
                List.of(ReturnLine.of(skuId, "商品一", new BigDecimal("990"), quantity)),
                status, null, NOW, null, null, null, 0L);
    }

    private static OpenReturnCommand openCommand(long skuId, int quantity) {
        return new OpenReturnCommand(ORDER_NO, USER, "req-" + skuId + "-" + quantity,
                List.of(new OpenReturnCommand.Item(skuId, quantity)),
                ReturnReason.CHANGED_MIND, null);
    }

    @Nested
    @DisplayName("可退數量——防重複退款的第二層")
    class ReturnableQuantity {

        @Test
        @DisplayName("審核中的退貨單也佔用額度，否則可在審核期間開第二張單重複退")
        void inFlightRequestConsumesQuota() {
            when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));
            // SKU 1 共買 2 件，已有一張「申請中」的單退了 2 件
            when(returnRepository.findByOrderNo(ORDER_NO))
                    .thenReturn(List.of(existingReturn(ReturnStatus.REQUESTED, 1L, 2)));

            assertThatThrownBy(() -> service.open(openCommand(1L, 1)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RETURN_QUANTITY_EXCEEDED);
        }

        @Test
        @DisplayName("被駁回的單釋放額度，否則被駁回一次就永遠不能再申請")
        void rejectedRequestReleasesQuota() {
            when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));
            when(returnRepository.findByOrderNo(ORDER_NO))
                    .thenReturn(List.of(existingReturn(ReturnStatus.REJECTED, 1L, 2)));

            ReturnRequestView view = service.open(openCommand(1L, 2));

            assertThat(view.lines()).singleElement()
                    .extracting(ReturnRequestView.Line::quantity).isEqualTo(2);
        }

        @Test
        @DisplayName("超過訂單行的數量會被拒絕")
        void refuseMoreThanOrdered() {
            when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));

            assertThatThrownBy(() -> service.open(openCommand(1L, 3)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RETURN_QUANTITY_EXCEEDED);
        }

        @Test
        @DisplayName("同一張申請裡重複列出同一個 SKU 時額度要連續扣減")
        void quotaDecrementsWithinOneRequest() {
            when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));

            // 兩行各退 2 件，合計 4 件 > 訂單的 2 件。
            // 若各自對照原始餘額，兩行都會通過
            OpenReturnCommand command = new OpenReturnCommand(ORDER_NO, USER, "req-dup",
                    List.of(new OpenReturnCommand.Item(1L, 2),
                            new OpenReturnCommand.Item(1L, 2)),
                    ReturnReason.CHANGED_MIND, null);

            assertThatThrownBy(() -> service.open(command))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RETURN_QUANTITY_EXCEEDED);
        }

        @Test
        @DisplayName("訂單上沒有的 SKU 不能退")
        void refuseUnknownSku() {
            when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));

            assertThatThrownBy(() -> service.open(openCommand(999L, 1)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
        }
    }

    @Nested
    @DisplayName("可退清單——畫面說可以退，送出就不該被拒絕")
    class ReturnableInspection {

        @Test
        @DisplayName("扣掉審核中的退貨單後回報剩餘可退數量")
        void reportsRemainingAfterInFlightRequests() {
            when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));
            when(returnRepository.findByOrderNo(ORDER_NO))
                    .thenReturn(List.of(existingReturn(ReturnStatus.REQUESTED, 1L, 1)));

            ReturnableView view = service.inspectReturnable(ORDER_NO, USER);

            assertThat(view.returnable()).isTrue();
            assertThat(view.requiresGoodsReturn()).isTrue();
            assertThat(view.lines())
                    .extracting(ReturnableView.Line::skuId,
                            ReturnableView.Line::orderedQuantity,
                            ReturnableView.Line::returnableQuantity)
                    // SKU 1 買 2 件、已申請 1 件，還能退 1 件
                    .containsExactly(tuple(1L, 2, 1), tuple(2L, 1, 1));
        }

        @Test
        @DisplayName("全部申請過之後整張訂單不可再退，且說得出原因")
        void notReturnableOnceEverythingIsClaimed() {
            when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));
            when(returnRepository.findByOrderNo(ORDER_NO)).thenReturn(List.of(
                    existingReturn(ReturnStatus.REFUNDED, 1L, 2),
                    existingReturn(ReturnStatus.APPROVED, 2L, 1)));

            ReturnableView view = service.inspectReturnable(ORDER_NO, USER);

            assertThat(view.returnable()).isFalse();
            assertThat(view.reason()).isNotBlank();
        }

        @Test
        @DisplayName("待付款的訂單回報不可退，並指出正確的操作是取消")
        void explainsWhyPendingPaymentCannotBeReturned() {
            Optional<Order> pending = Optional.of(order(OrderStatus.PENDING_PAYMENT));
            when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(pending);
            when(orderRepository.findByOrderNo(any())).thenReturn(pending);

            ReturnableView view = service.inspectReturnable(ORDER_NO, USER);

            assertThat(view.returnable()).isFalse();
            assertThat(view.reason()).contains("取消");
        }

        @Test
        @DisplayName("未出貨的訂單免寄回，這件事要在申請前就講明")
        void flagsNoGoodsReturnBeforeShipping() {
            when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(Optional.of(order(OrderStatus.PAID)));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order(OrderStatus.PAID)));

            assertThat(service.inspectReturnable(ORDER_NO, USER).requiresGoodsReturn()).isFalse();
        }
    }

    @Nested
    @DisplayName("哪些訂單可以退")
    class Returnability {

        @Test
        @DisplayName("待付款的訂單不能退——錢都還沒收，正確操作是取消")
        void refusePendingPayment() {
            Optional<Order> pending = Optional.of(order(OrderStatus.PENDING_PAYMENT));
            when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(pending);
            when(orderRepository.findByOrderNo(any())).thenReturn(pending);

            assertThatThrownBy(() -> service.open(openCommand(1L, 1)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_RETURNABLE);
        }

        @Test
        @DisplayName("未出貨時免寄回——貨還在倉庫，沒有東西要寄回來")
        void paidOrderNeedsNoGoodsReturn() {
            when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(Optional.of(order(OrderStatus.PAID)));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order(OrderStatus.PAID)));

            assertThat(service.open(openCommand(1L, 1)).requiresGoodsReturn()).isFalse();
        }

        @Test
        @DisplayName("已出貨與已完成都需要寄回")
        void shippedOrderRequiresGoodsReturn() {
            when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));
            assertThat(service.open(openCommand(1L, 1)).requiresGoodsReturn()).isTrue();

            when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(Optional.of(order(OrderStatus.COMPLETED)));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order(OrderStatus.COMPLETED)));
            assertThat(service.open(openCommand(1L, 1)).requiresGoodsReturn()).isTrue();
        }

        @Test
        @DisplayName("別人的訂單一律回「不存在」，不回「無權限」")
        void otherUsersOrderLooksMissing() {
            when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));

            OpenReturnCommand command = new OpenReturnCommand(ORDER_NO, USER + 1, "req-other",
                    List.of(new OpenReturnCommand.Item(1L, 1)), ReturnReason.CHANGED_MIND, null);

            assertThatThrownBy(() -> service.open(command))
                    .isInstanceOf(BusinessException.class)
                    // 回 FORBIDDEN 等於確認這個單號是有效的
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("退款")
    class Refund {

        private ReturnRequest approvedReturn(int quantity) {
            ReturnRequest request = ReturnRequest.restore(1L, ReturnNo.of(RETURN_NO),
                    OrderNo.of(ORDER_NO), USER, "req-approved", ReturnReason.CHANGED_MIND, null, false,
                    List.of(ReturnLine.of(1L, "商品一", new BigDecimal("990"), quantity)),
                    ReturnStatus.APPROVED, null, NOW, NOW, null, null, 0L);
            when(returnRepository.findByReturnNo(any())).thenReturn(Optional.of(request));
            return request;
        }

        private Payment paidPayment() {
            Payment payment = Payment.initiate(PaymentNo.of("PAY-220956648921890816"),
                    OrderNo.of(ORDER_NO), USER, new BigDecimal("2480"), NOW);
            payment.markSucceeded("GW-TXN-001", NOW);
            payment.pullDomainEvents();
            when(paymentRepository.findByOrderNo(any())).thenReturn(Optional.of(payment));
            return payment;
        }

        @Test
        @DisplayName("部分退款不改訂單狀態——沒退的那些行還可以出貨、還可以再申請")
        void partialRefundLeavesOrderAlone() {
            approvedReturn(1);
            paidPayment();
            when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(Optional.of(order(OrderStatus.COMPLETED)));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order(OrderStatus.COMPLETED)));

            service.refund(RETURN_NO);

            verify(orderRepository, never()).update(any());
        }

        @Test
        @DisplayName("全額退完才把訂單轉為 REFUNDED，且判斷來自付款聚合根而非重算一次")
        void fullRefundClosesTheOrder() {
            // 2 件 × 990 + 1 件 × 500 = 2480，剛好是訂單總額
            ReturnRequest request = ReturnRequest.restore(1L, ReturnNo.of(RETURN_NO),
                    OrderNo.of(ORDER_NO), USER, "req-approved", ReturnReason.CHANGED_MIND, null, false,
                    List.of(ReturnLine.of(1L, "商品一", new BigDecimal("990"), 2),
                            ReturnLine.of(2L, "商品二", new BigDecimal("500"), 1)),
                    ReturnStatus.APPROVED, null, NOW, NOW, null, null, 0L);
            when(returnRepository.findByReturnNo(any())).thenReturn(Optional.of(request));
            paidPayment();
            Order order = order(OrderStatus.COMPLETED);
            when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(Optional.of(order));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order));

            service.refund(RETURN_NO);

            verify(orderRepository).update(any());
            assertThat(order.status()).isEqualTo(OrderStatus.REFUNDED);
        }

        @Test
        @DisplayName("退款事件會寫進 outbox——金流呼叫留在消費端，不在這個交易裡")
        void refundGoesThroughOutbox() {
            approvedReturn(1);
            paidPayment();
            when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(Optional.of(order(OrderStatus.COMPLETED)));
            when(orderRepository.findByOrderNo(any())).thenReturn(Optional.of(order(OrderStatus.COMPLETED)));

            service.refund(RETURN_NO);

            ArgumentCaptor<List<com.flashsale.domain.shared.DomainEvent>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(eventOutbox).append(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
        }

        @Test
        @DisplayName("沒有付款紀錄就無從退款")
        void refuseRefundWithoutPayment() {
            approvedReturn(1);
            when(paymentRepository.findByOrderNo(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.refund(RETURN_NO))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_NOT_FOUND);
        }
    }
}
