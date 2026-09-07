package com.flashsale.application.service;

import com.flashsale.application.config.SeckillPolicy;
import com.flashsale.application.port.in.OrderAdminUseCase;
import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.application.port.in.dto.PageView;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.PaymentRepository;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.OrderStatus;
import com.flashsale.domain.payment.Payment;
import com.flashsale.domain.payment.PaymentStatus;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.domain.shared.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
public class OrderAdminService implements OrderAdminUseCase {

    private static final Logger log = LoggerFactory.getLogger(OrderAdminService.class);
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_REASON_LENGTH = 100;

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ActivityRepository activityRepository;
    private final OrderCloser orderCloser;
    private final SeckillPolicy policy;
    private final Clock clock;

    public OrderAdminService(OrderRepository orderRepository,
                             PaymentRepository paymentRepository,
                             ActivityRepository activityRepository,
                             OrderCloser orderCloser,
                             SeckillPolicy policy,
                             Clock clock) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.activityRepository = activityRepository;
        this.orderCloser = orderCloser;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<OrderView> search(String orderNo, Long userId, String status, int page, int size) {
        Page paging = Page.of(page, size, MAX_PAGE_SIZE);
        OrderRepository.SearchCriteria criteria = new OrderRepository.SearchCriteria(
                blankToNull(orderNo), userId, parseStatus(status));
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
        Instant now = clock.instant();
        // 行鎖只保證兩邊不會同時寫（靠 @Version）。付款回調用的是非鎖定讀，
        // 它會在我們 commit 之後才撞上版本衝突而整個回滾——連「錢已收到」都一起消失。
        // 所以有付款在途時一律不關：15 分鐘後逾時排程自然會處理
        Order order = orderRepository.findByOrderNoForUpdate(OrderNo.of(orderNo))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        rejectIfPaymentInFlight(order.orderNo());
        rejectIfActivitySettled(order, now);
        orderCloser.close(order, "客服關單：" + reason.trim(), now);
        log.info("後台關閉訂單 {}：{}", orderNo, reason.trim());
        return OrderView.from(order);
    }

    private void rejectIfPaymentInFlight(OrderNo orderNo) {
        Optional<Payment> payment = paymentRepository.findByOrderNo(orderNo);
        if (payment.isPresent() && payment.get().status() != PaymentStatus.FAILED) {
            throw new BusinessException(ErrorCode.ILLEGAL_ORDER_STATE_TRANSITION,
                    "這張訂單有付款進行中或已完成，不可手動關閉");
        }
    }

    /**
     * 活動結算（釋放）之後退回的秒殺庫存不會再被算進去，等於永久少賣一件。
     * 逾時排程不會走到這裡——它在 15 分鐘內就關了；只有後台手動關單沒有時間上界。
     */
    private void rejectIfActivitySettled(Order order, Instant now) {
        for (OrderLine line : order.lines()) {
            if (line.sourceActivityId() == null) {
                continue;
            }
            Optional<SeckillActivity> activity = activityRepository.findById(line.sourceActivityId());
            if (activity.isPresent()
                    && activity.get().period().endAt().plus(policy.stockKeyTtlBuffer()).isBefore(now)) {
                throw new BusinessException(ErrorCode.ILLEGAL_ORDER_STATE_TRANSITION,
                        "活動庫存已結算，這張秒殺訂單不可再手動關閉");
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 打錯的狀態要回參數錯誤，不是安靜回一頁空的。 */
    private static String parseStatus(String status) {
        String trimmed = blankToNull(status);
        if (trimmed == null) {
            return null;
        }
        try {
            return OrderStatus.valueOf(trimmed.toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "參數「status」的值不正確");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> traceId(String orderNo) {
        OrderNo no = OrderNo.of(orderNo);
        orderRepository.findByOrderNo(no).orElseThrow(() -> new BusinessException(
                ErrorCode.ORDER_NOT_FOUND, "訂單 %s 不存在".formatted(orderNo)));
        return orderRepository.findTraceId(no);
    }
}
