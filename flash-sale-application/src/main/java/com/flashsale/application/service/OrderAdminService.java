package com.flashsale.application.service;

import com.flashsale.application.port.in.OrderAdminUseCase;
import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.application.port.in.dto.PageView;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.domain.shared.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class OrderAdminService implements OrderAdminUseCase {

    private static final Logger log = LoggerFactory.getLogger(OrderAdminService.class);
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_REASON_LENGTH = 100;

    private final OrderRepository orderRepository;
    private final OrderCloser orderCloser;
    private final Clock clock;

    public OrderAdminService(OrderRepository orderRepository, OrderCloser orderCloser, Clock clock) {
        this.orderRepository = orderRepository;
        this.orderCloser = orderCloser;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<OrderView> search(String orderNo, Long userId, String status, int page, int size) {
        Page paging = Page.of(page, size, MAX_PAGE_SIZE);
        OrderRepository.SearchCriteria criteria = new OrderRepository.SearchCriteria(
                blankToNull(orderNo), userId, blankToNull(status));
        var items = orderRepository.search(criteria, paging.size(), paging.offset()).stream()
                .map(OrderView::from)
                .toList();
        return PageView.of(items, orderRepository.countSearch(criteria), paging.number(), paging.size());
    }

    @Override
    @Transactional(readOnly = true)
    public OrderView find(String orderNo) {
        return orderRepository.findByOrderNo(OrderNo.of(orderNo))
                .map(OrderView::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    @Override
    @Transactional
    public OrderView close(String orderNo, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "關單必須填寫原因");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "關單原因不可超過 100 字");
        }
        // 鎖列：付款回調與逾時排程可能同時在動這張單，關單不可以贏過已收到的錢
        Order order = orderRepository.findByOrderNoForUpdate(OrderNo.of(orderNo))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        orderCloser.close(order, "客服關單：" + reason.trim(), clock.instant());
        log.info("後台關閉訂單 {}：{}", orderNo, reason.trim());
        return OrderView.from(order);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
