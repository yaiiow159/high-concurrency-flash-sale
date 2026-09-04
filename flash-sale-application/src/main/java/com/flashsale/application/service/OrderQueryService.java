package com.flashsale.application.service;

import com.flashsale.application.port.in.OrderQueryUseCase;
import com.flashsale.application.port.in.dto.OrderView;

import java.util.List;
import com.flashsale.application.port.out.OrderQueueDepth;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.SeckillRequestTracker;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 訂單查詢服務。
 *
 * <p>核心職責是消弭非同步下單的「時間差空窗」：訂單號已回給前端、庫存也扣了，
 * 但消費端還沒落庫。此時查 DB 會撲空，若直接回 404，使用者會誤以為沒搶到。
 * 這裡改為查詢受理紀錄，回覆 {@code PROCESSING} 讓前端繼續輪詢。
 */
@Service
public class OrderQueryService implements OrderQueryUseCase {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final OrderRepository orderRepository;
    private final SeckillRequestTracker requestTracker;
    private final OrderQueueDepth queueDepth;

    public OrderQueryService(OrderRepository orderRepository, SeckillRequestTracker requestTracker,
                             OrderQueueDepth queueDepth) {
        this.orderRepository = orderRepository;
        this.requestTracker = requestTracker;
        this.queueDepth = queueDepth;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderView findByOrderNo(String orderNo, Long userId) {
        Optional<Order> persisted = orderRepository.findByOrderNo(OrderNo.of(orderNo));
        if (persisted.isPresent()) {
            Order order = persisted.get();
            ensureOwnedBy(order.userId(), userId, orderNo);
            return OrderView.from(order);
        }
        return resolveFromTracker(orderNo, userId);
    }

    private OrderView resolveFromTracker(String orderNo, Long userId) {
        SeckillRequestTracker.RequestStatus status = requestTracker.find(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        if (status.failed()) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, status.reason());
        }
        ensureOwnedBy(status.userId(), userId, orderNo);
        // 排隊資訊取自已經算好的佇列深度（ADR-0023），不額外查任何東西。
        // 使用者不會因為要等四十分鐘就原諒你，但他至少不會以為系統壞了
        return OrderView.processing(orderNo,
                new OrderView.Queue(queueDepth.backlog(), queueDepth.estimatedWaitSeconds()));
    }

    /**
     * 越權查詢一律回「訂單不存在」而非「無權限」。
     *
     * <p>回傳「無權限」等於告訴攻擊者這個訂單號真的存在，可被用來枚舉訂單量。
     */
    private void ensureOwnedBy(Long ownerId, Long requesterId, String orderNo) {
        if (ownerId != null && !ownerId.equals(requesterId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
    }

    /**
     * 訂單列表。
     *
     * <p>頁大小夾在 50：這是登入後就能無限次呼叫的端點，
     * 沒有上限的話任何人都能用 {@code size=1000000} 讓資料庫掃全表。
     */
    @Override
    @Transactional(readOnly = true)
    public List<OrderView> listForUser(Long userId, String status, int page, int size) {
        int safeSize = Math.clamp(size <= 0 ? DEFAULT_PAGE_SIZE : size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        return orderRepository.findByUserId(userId, status, safeSize, safePage * safeSize).stream()
                .map(OrderView::from)
                .toList();
    }
}
