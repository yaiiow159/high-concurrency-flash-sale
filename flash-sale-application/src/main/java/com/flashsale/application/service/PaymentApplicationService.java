package com.flashsale.application.service;

import com.flashsale.application.port.in.PaymentUseCase;
import com.flashsale.application.port.in.dto.PaymentIntentView;
import com.flashsale.application.port.in.dto.PaymentView;
import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.PaymentGateway;
import com.flashsale.application.port.out.PaymentNoGenerator;
import com.flashsale.application.port.out.PaymentRepository;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.OrderStatus;
import com.flashsale.domain.order.SeckillOrder;
import com.flashsale.domain.payment.Payment;
import com.flashsale.domain.payment.PaymentNo;
import com.flashsale.domain.payment.PaymentStatus;
import com.flashsale.domain.payment.event.PaymentInitiatedSignal;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 付款服務。
 *
 * <p>整個流程中最需要想清楚的是<b>「錢收了但訂單入不了帳」</b>這個競態：
 * 使用者完成付款的同時，逾時關單排程正好把訂單取消。
 *
 * <pre>
 *   t0  使用者按下付款，跳轉金流頁面
 *   t1  逾時關單排程執行 → 訂單 CANCELLED、庫存退回
 *   t2  使用者完成付款 → 閘道回調「成功」
 *       此時錢已經收了，但訂單已是終態，無法轉為 PAID
 * </pre>
 *
 * <p>三種處理方式與取捨：
 * <ul>
 *   <li><b>把付款標記為失敗</b>——錯的。錢真的收了，帳上寫「沒收到」會讓對帳與現實脫節</li>
 *   <li><b>強制把訂單改回 PAID</b>——更錯。庫存已經退回並可能被別人買走，
 *       這會製造一張沒有庫存支撐的訂單，也就是超賣</li>
 *   <li><b>如實記錄收款成功，再標記待退款</b>——本方案</li>
 * </ul>
 *
 * <p>這個競態<b>無法完全消除</b>，只能縮小窗口（付款期限拉長）並確保發生時能被正確處理。
 */
@Service
public class PaymentApplicationService implements PaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(PaymentApplicationService.class);

    /** 閘道回調中承載付款單號與交易編號的參數名。 */
    private static final String PARAM_PAYMENT_NO = "paymentNo";
    private static final String PARAM_TRANSACTION_ID = "transactionId";
    private static final String PARAM_RESULT = "result";
    private static final String RESULT_SUCCESS = "SUCCESS";

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentNoGenerator paymentNoGenerator;
    private final EventOutbox eventOutbox;
    private final PaymentMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public PaymentApplicationService(PaymentRepository paymentRepository,
                                     OrderRepository orderRepository,
                                     PaymentGateway paymentGateway,
                                     PaymentNoGenerator paymentNoGenerator,
                                     EventOutbox eventOutbox,
                                     PaymentMetrics metrics,
                                     ApplicationEventPublisher eventPublisher,
                                     Clock clock) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.paymentGateway = paymentGateway;
        this.paymentNoGenerator = paymentNoGenerator;
        this.eventOutbox = eventOutbox;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PaymentIntentView initiate(String orderNo, Long userId) {
        SeckillOrder order = requireOwnedOrder(OrderNo.of(orderNo), userId);
        if (order.status() != OrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE,
                    "訂單目前為 %s，無法付款".formatted(order.status()));
        }

        Payment payment = paymentRepository.findByOrderNo(order.orderNo())
                .map(existing -> reuseOrRetry(existing))
                .orElseGet(() -> createPayment(order));

        PaymentGateway.PaymentIntent intent = paymentGateway.initiate(payment);
        metrics.recordInitiated(payment.status().name());

        // 行程內訊號，供模擬閘道知道「該送回調了」。接上真實金流後，
        // 回調由外部系統送來，這一行連同 SimulatedCallbackDispatcher 一併刪除即可。
        eventPublisher.publishEvent(new PaymentInitiatedSignal(
                payment.paymentNo().value(), orderNo));
        return new PaymentIntentView(payment.paymentNo().value(), orderNo,
                intent.paymentUrl(), payment.status().name());
    }

    /**
     * 重複發起時沿用既有付款單。
     *
     * <p>使用者連點兩次「去付款」不該產生兩張付款單——那會讓對帳時
     * 看到一張訂單對應多筆收款，無從判斷哪些是重複、哪些是真的收了兩次。
     */
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

    private Payment createPayment(SeckillOrder order) {
        // 金額取自訂單，不接受呼叫端傳入——否則前端就能自己決定要付多少
        return paymentRepository.save(Payment.initiate(
                paymentNoGenerator.next(), order.orderNo(), order.userId(),
                order.amount(), clock.instant()));
    }

    @Override
    @Transactional
    public void handleGatewayCallback(Map<String, String> parameters) {
        // 先驗簽，再看內容。這個端點對外開放，
        // 少了這一步，任何人送一個「付款成功」就能免費下單。
        if (!paymentGateway.verifyCallbackSignature(parameters)) {
            metrics.recordCallback("invalid-signature");
            log.warn("付款回調簽章驗證失敗，已拒絕。參數鍵={}", parameters.keySet());
            throw new BusinessException(ErrorCode.INVALID_CALLBACK_SIGNATURE);
        }

        Payment payment = paymentRepository.findByPaymentNo(
                        PaymentNo.of(parameters.get(PARAM_PAYMENT_NO)))
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        // 冪等：閘道會重送回調，有些送三、四次。已終結的付款直接略過。
        if (payment.isAlreadySettled()) {
            metrics.recordCallback("duplicate");
            log.debug("付款 {} 已為 {}，略過重複回調", payment.paymentNo(), payment.status());
            return;
        }

        Instant now = clock.instant();
        if (RESULT_SUCCESS.equals(parameters.get(PARAM_RESULT))) {
            applySuccess(payment, parameters.get(PARAM_TRANSACTION_ID), now);
        } else {
            payment.markFailed(parameters.getOrDefault("failureReason", "閘道回報付款失敗"), now);
            paymentRepository.save(payment);
            metrics.recordCallback("failed");
        }
    }

    /**
     * 套用收款成功的結果。
     *
     * <p>順序不可顛倒：<b>先如實記錄收款成功，再嘗試讓訂單入帳</b>。
     * 反過來的話，訂單入帳失敗時付款單還停在 PENDING，
     * 帳上會顯示「沒收到錢」而現實是收到的。
     */
    private void applySuccess(Payment payment, String transactionId, Instant now) {
        payment.markSucceeded(transactionId, now);

        Optional<SeckillOrder> order = orderRepository.findByOrderNo(payment.orderNo());
        if (order.isPresent() && order.get().status() == OrderStatus.PENDING_PAYMENT) {
            settleOrder(order.get(), payment, now);
            return;
        }

        // 走到這裡代表競態發生了：錢收了，但訂單已被關閉或不存在。
        String reason = order.map(o -> "付款完成時訂單已為 " + o.status())
                .orElse("付款完成時查無此訂單");
        payment.markRefundRequired(reason, now);
        paymentRepository.save(payment);
        // 此時會有兩個事件：收款成功 + 需要退款。兩個都要發。
        // 只發退款事件會讓下游財務系統看到一筆沒有對應收入的支出——
        // 錢確實進來過，就必須如實記錄，再記錄它出去。
        eventOutbox.append(payment.pullDomainEvents());

        metrics.recordCallback("refund-required");
        // 這代表有一筆錢卡在系統裡，必須被監控抓到
        log.error("收款成功但無法入帳，已標記待退款 paymentNo={}, orderNo={}, 原因={}",
                payment.paymentNo(), payment.orderNo(), reason);
    }

    private void settleOrder(SeckillOrder order, Payment payment, Instant now) {
        order.pay(now);
        orderRepository.update(order);
        paymentRepository.save(payment);

        // 訂單與付款的事件都在同一個交易內寫入 Outbox，commit 成功即保證會被投遞
        eventOutbox.append(order.pullDomainEvents());
        eventOutbox.append(payment.pullDomainEvents());

        metrics.recordCallback("settled");
        log.info("付款完成並已入帳 paymentNo={}, orderNo={}", payment.paymentNo(), payment.orderNo());
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentView findByOrderNo(String orderNo, Long userId) {
        requireOwnedOrder(OrderNo.of(orderNo), userId);
        return paymentRepository.findByOrderNo(OrderNo.of(orderNo))
                .map(PaymentView::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    /**
     * 取出訂單並確認歸屬。
     *
     * <p>越權一律回「訂單不存在」而非「無權限」——後者等於告訴攻擊者
     * 這個訂單號真的存在，可被用來枚舉訂單量。與 {@code OrderQueryService} 一致。
     */
    private SeckillOrder requireOwnedOrder(OrderNo orderNo, Long userId) {
        SeckillOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.userId().equals(userId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    /** 供退款排程使用：撈出所有卡住的收款。 */
    @Transactional(readOnly = true)
    public List<Payment> findPendingRefunds(int limit) {
        return paymentRepository.findPendingRefunds(limit);
    }
}
