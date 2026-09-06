package com.flashsale.application.service;

import com.flashsale.application.port.in.OrderStaffNoteUseCase;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 訂單內部註記。 */
@Service
public class OrderStaffNoteService implements OrderStaffNoteUseCase {

    private static final Logger log = LoggerFactory.getLogger(OrderStaffNoteService.class);

    private static final int MAX_LENGTH = 500;

    private final OrderRepository orderRepository;

    public OrderStaffNoteService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public String read(String orderNo) {
        requireOrder(orderNo);
        return orderRepository.findStaffNote(OrderNo.of(orderNo)).orElse(null);
    }

    @Override
    @Transactional
    public void write(String orderNo, String note) {
        requireOrder(orderNo);
        if (note != null && note.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "內部註記不可超過 " + MAX_LENGTH + " 字");
        }
        String normalized = note == null || note.isBlank() ? null : note.trim();
        orderRepository.updateStaffNote(OrderNo.of(orderNo), normalized);
        log.info("訂單 {} 的內部註記已更新", orderNo);
    }

    private void requireOrder(String orderNo) {
        orderRepository.findByOrderNo(OrderNo.of(orderNo))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }
}
