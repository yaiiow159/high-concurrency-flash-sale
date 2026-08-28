package com.flashsale.application.service;

import com.flashsale.application.port.in.OrderQueryUseCase;
import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.SeckillRequestTracker;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.SeckillOrder;
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

    private final OrderRepository orderRepository;
    private final SeckillRequestTracker requestTracker;

    public OrderQueryService(OrderRepository orderRepository, SeckillRequestTracker requestTracker) {
        this.orderRepository = orderRepository;
        this.requestTracker = requestTracker;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderView findByOrderNo(String orderNo, Long userId) {
        Optional<SeckillOrder> persisted = orderRepository.findByOrderNo(OrderNo.of(orderNo));
        if (persisted.isPresent()) {
            SeckillOrder order = persisted.get();
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
        return OrderView.processing(orderNo);
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
}
