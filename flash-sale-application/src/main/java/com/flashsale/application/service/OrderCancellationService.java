package com.flashsale.application.service;

import com.flashsale.application.port.in.CancelOrderUseCase;
import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** 買家取消訂單。退庫走 {@link OrderCloser}，與逾時關單、後台關單是同一條路。 */
@Service
public class OrderCancellationService implements CancelOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(OrderCancellationService.class);
    private static final String CLOSE_REASON = "買家取消訂單";

    private final OrderRepository orderRepository;
    private final ManualCloseGuard closeGuard;
    private final OrderCloser orderCloser;
    private final Clock clock;

    public OrderCancellationService(OrderRepository orderRepository,
                                    ManualCloseGuard closeGuard,
                                    OrderCloser orderCloser,
                                    Clock clock) {
        this.orderRepository = orderRepository;
        this.closeGuard = closeGuard;
        this.orderCloser = orderCloser;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OrderView cancel(String orderNo, Long userId) {
        Instant now = clock.instant();
        // 行鎖：連點兩下時第二個請求要等第一個 commit，然後被狀態機擋下，而不是退兩次庫
        Order order = orderRepository.findByOrderNoForUpdate(OrderNo.of(orderNo))
                .filter(found -> found.belongsTo(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        closeGuard.ensureClosable(order, now);
        orderCloser.close(order, CLOSE_REASON, now);
        log.info("買家 {} 取消訂單 {}", userId, orderNo);
        return OrderView.from(order);
    }
}
