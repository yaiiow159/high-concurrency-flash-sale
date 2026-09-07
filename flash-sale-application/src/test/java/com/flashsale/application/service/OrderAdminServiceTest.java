package com.flashsale.application.service;

import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.application.port.out.InventoryService;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderChannel;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.OrderStatus;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("後台訂單管理")
class OrderAdminServiceTest {

    private static final String ORDER_NO = "222633466569687040";
    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("5990.00");

    private OrderRepository orderRepository;
    private InventoryService inventoryService;
    private EventOutbox eventOutbox;
    private OrderAdminService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        inventoryService = mock(InventoryService.class);
        eventOutbox = mock(EventOutbox.class);
        service = new OrderAdminService(orderRepository,
                new OrderCloser(orderRepository, inventoryService, eventOutbox),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 一行秒殺、一行一般：關單時只有一般那行要立刻退庫，秒殺那行走事件。 */
    private static Order order(OrderStatus status) {
        return Order.restore(OrderNo.of(ORDER_NO), 7L, OrderChannel.NORMAL, "req-1",
                List.of(new OrderLine(2001L, "秒殺商品", AMOUNT, 1, 1001L),
                        new OrderLine(2002L, "一般商品", AMOUNT, 2, null)),
                AMOUNT.multiply(BigDecimal.valueOf(3)), null, status, NOW.minusSeconds(600), null, null, 0L);
    }

    @Test
    @DisplayName("待付款：關單、退一般庫存、寫入退庫事件，三件事一起發生")
    void closesPendingOrder() {
        Order order = order(OrderStatus.PENDING_PAYMENT);
        when(orderRepository.findByOrderNoForUpdate(OrderNo.of(ORDER_NO))).thenReturn(Optional.of(order));
        when(orderRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        OrderView view = service.close(ORDER_NO, "客戶來電取消");

        assertThat(view.status()).isEqualTo("CANCELLED");
        assertThat(view.closeReason()).isEqualTo("客服關單：客戶來電取消");
        verify(orderRepository).update(order);
        // 只有一般那一行直接退；秒殺那行靠事件，不可在這裡動 Redis
        verify(inventoryService).restore(any());
        verify(eventOutbox).append(anyList());
    }

    @Test
    @DisplayName("已付款：拒絕，什麼都不動——關單會退庫存卻不退錢")
    void refusesPaidOrder() {
        when(orderRepository.findByOrderNoForUpdate(OrderNo.of(ORDER_NO)))
                .thenReturn(Optional.of(order(OrderStatus.PAID)));

        assertThatThrownBy(() -> service.close(ORDER_NO, "誤按"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ILLEGAL_ORDER_STATE_TRANSITION);

        verify(orderRepository, never()).update(any());
        verify(inventoryService, never()).restore(any());
        verify(eventOutbox, never()).append(anyList());
    }

    @Test
    @DisplayName("原因必填：沒有原因的關單事後沒人說得出為什麼")
    void requiresReason() {
        assertThatThrownBy(() -> service.close(ORDER_NO, "  "))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PARAMETER);

        verify(orderRepository, never()).findByOrderNoForUpdate(any());
    }

    @Test
    @DisplayName("不存在：ORDER_NOT_FOUND")
    void notFound() {
        when(orderRepository.findByOrderNoForUpdate(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.close(ORDER_NO, "test"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("搜尋：空白條件視為沒有條件，總筆數一起回")
    void searchNormalizesBlankFilters() {
        when(orderRepository.search(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(order(OrderStatus.PAID)));
        when(orderRepository.countSearch(any())).thenReturn(42L);

        var page = service.search("  ", null, "", 0, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(42L);
        verify(orderRepository).search(new OrderRepository.SearchCriteria(null, null, null), 20, 0);
    }
}
