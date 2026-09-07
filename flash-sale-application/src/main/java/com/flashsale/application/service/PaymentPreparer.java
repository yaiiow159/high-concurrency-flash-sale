package com.flashsale.application.service;

import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.PaymentNoGenerator;
import com.flashsale.application.port.out.PaymentRepository;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.OrderStatus;
import com.flashsale.domain.payment.Payment;
import com.flashsale.domain.payment.PaymentStatus;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 取回或建立付款單——發起付款流程中唯一需要交易的那一段。
 *
 * <p><b>刻意與 {@code PaymentApplicationService} 拆成兩個 Bean。</b>
 * 閘道呼叫是遠端的，不可以被包進交易（連線池會先被抽乾）；而同一個 Bean 內部
 * 呼叫 {@code this.method()} 不會經過代理，{@code @Transactional} 會安靜失效（鐵則 6）。
 * 與 {@code OutboxRelayScheduler} / {@code OutboxRelayer} 的拆分同理。
 */
@Service
public class PaymentPreparer {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentNoGenerator paymentNoGenerator;
    private final Clock clock;

    public PaymentPreparer(OrderRepository orderRepository,
                           PaymentRepository paymentRepository,
                           PaymentNoGenerator paymentNoGenerator,
                           Clock clock) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.paymentNoGenerator = paymentNoGenerator;
        this.clock = clock;
    }

    @Transactional
    public Payment prepare(String orderNo, Long userId) {
        Order order = requireOwnedOrder(OrderNo.of(orderNo), userId);
        if (order.status() != OrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE,
                    "訂單目前為 %s，無法付款".formatted(order.status()));
        }
        return paymentRepository.findByOrderNo(order.orderNo())
                .map(this::reuseOrRetry)
                .orElseGet(() -> createPayment(order));
    }

    /** 重複發起時沿用既有付款單。 */
    private Payment reuseOrRetry(Payment existing) {
        if (existing.status() == PaymentStatus.FAILED) {
            existing.retry(clock.instant());
            return paymentRepository.save(existing);
        }
        if (existing.status().moneyReceived()) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE, "此訂單已完成付款");
        }
        return existing;
    }

    private Payment createPayment(Order order) {
        // 金額取自訂單，不接受呼叫端傳入——否則前端就能自己決定要付多少。
        //
        // **用 payableAmount() 而不是 totalAmount()**：後者不含運費（ADR-0019 決策 2）。
        // 用錯的話運費就收不到，而且**沒有任何東西會發現**——
        // 付款成功、訂單完成、貨也寄了，只有月底對帳時才發現每一單都少收幾十元。
        //
        // 退款上限跟著這個金額走，因此這一行同時決定了「運費退不退得出來」。
        return paymentRepository.save(Payment.initiate(
                paymentNoGenerator.next(), order.orderNo(), order.userId(),
                order.payableAmount(), clock.instant()));
    }

    /** 查不到與不是本人一律回「不存在」：區分開來等於提供一支訂單號枚舉的 API。 */
    private Order requireOwnedOrder(OrderNo orderNo, Long userId) {
        return orderRepository.findByOrderNo(orderNo)
                .filter(order -> order.belongsTo(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }
}
