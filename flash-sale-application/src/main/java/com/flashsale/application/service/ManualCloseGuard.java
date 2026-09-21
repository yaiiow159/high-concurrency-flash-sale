package com.flashsale.application.service;

import com.flashsale.application.config.SeckillPolicy;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.PaymentRepository;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.payment.Payment;
import com.flashsale.domain.payment.PaymentStatus;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * 「人為關單」的共同前提，後台關單與買家取消共用。
 * 逾時排程不走這裡：它有時間上界（付款期限一到就關），這兩條檢查對它恆為真。
 */
@Service
public class ManualCloseGuard {

    private final PaymentRepository paymentRepository;
    private final ActivityRepository activityRepository;
    private final SeckillPolicy policy;

    public ManualCloseGuard(PaymentRepository paymentRepository,
                            ActivityRepository activityRepository,
                            SeckillPolicy policy) {
        this.paymentRepository = paymentRepository;
        this.activityRepository = activityRepository;
        this.policy = policy;
    }

    /** 呼叫端必須已經以行鎖取得訂單，否則兩個檢查之間仍然有空窗。 */
    public void ensureClosable(Order order, Instant now) {
        rejectIfPaymentInFlight(order);
        rejectIfActivitySettled(order, now);
    }

    /**
     * 付款回調用的是非鎖定讀：我們先 commit 的話，它會撞上版本衝突而整個回滾，
     * 連「錢已收到」都一起消失。有付款在途時一律不關，逾時排程自然會處理。
     */
    private void rejectIfPaymentInFlight(Order order) {
        Optional<Payment> payment = paymentRepository.findByOrderNo(order.orderNo());
        if (payment.isPresent() && payment.get().status() != PaymentStatus.FAILED) {
            throw new BusinessException(ErrorCode.ILLEGAL_ORDER_STATE_TRANSITION,
                    "這張訂單有付款進行中或已完成，不可關閉；未完成付款的訂單會在期限後自動取消");
        }
    }

    /** 活動結算（釋放）之後退回的秒殺庫存不會再被算進去，等於永久少賣一件。 */
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
}
