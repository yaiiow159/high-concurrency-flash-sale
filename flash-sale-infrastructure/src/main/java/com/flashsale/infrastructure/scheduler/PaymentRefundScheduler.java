package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.out.DistributedLock;
import com.flashsale.application.port.out.PaymentGateway;
import com.flashsale.application.port.out.PaymentRepository;
import com.flashsale.application.port.out.PaymentMetrics;
import com.flashsale.domain.payment.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** 處理待退款的收款。 */
@Component
public class PaymentRefundScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentRefundScheduler.class);

    private static final String LOCK_KEY = "seckill:lock:payment-refund";
    private static final Duration LOCK_LEASE = Duration.ofMinutes(5);
    private static final int BATCH_SIZE = 50;

    private final PaymentRefunder refunder;
    private final DistributedLock distributedLock;

    public PaymentRefundScheduler(PaymentRefunder refunder, DistributedLock distributedLock) {
        this.refunder = refunder;
        this.distributedLock = distributedLock;
    }

    // 單位為毫秒，與其他排程器一致——@Scheduled 的 fixedDelayString 不吃 "60s" 這種格式
    @Scheduled(fixedDelayString = "${flash-sale.payment.refund-scan-interval-ms:60000}")
    public void processPendingRefunds() {
        distributedLock.tryExecuteWithLock(LOCK_KEY, LOCK_LEASE, this::runSafely);
    }

    /** 排程方法絕不可讓例外逸出——Spring 會直接取消後續排程， 退款從此靜默停擺，而那些錢會一直卡著。 */
    private void runSafely() {
        try {
            int refunded = refunder.refundPending(BATCH_SIZE);
            if (refunded > 0) {
                log.info("本輪退款完成 {} 筆", refunded);
            }
        } catch (RuntimeException e) {
            log.error("退款處理失敗，下一輪將重試", e);
        }
    }

    /** 實際執行退款。 */
    @Component
    public static class PaymentRefunder {

        private static final Logger log = LoggerFactory.getLogger(PaymentRefunder.class);

        private final PaymentRepository paymentRepository;
        private final PaymentGateway paymentGateway;
        private final PaymentMetrics metrics;
        private final Clock clock;

        public PaymentRefunder(PaymentRepository paymentRepository,
                               PaymentGateway paymentGateway,
                               PaymentMetrics metrics,
                               Clock clock) {
            this.paymentRepository = paymentRepository;
            this.paymentGateway = paymentGateway;
            this.metrics = metrics;
            this.clock = clock;
        }

        @Transactional
        public int refundPending(int batchSize) {
            List<Payment> pending = paymentRepository.findPendingRefunds(batchSize);
            int refunded = 0;
            for (Payment payment : pending) {
                if (refundOne(payment)) {
                    refunded++;
                }
            }
            return refunded;
        }

        /** 單筆失敗不中斷整批——一筆退不掉不該讓其他人的錢也卡著。 */
        private boolean refundOne(Payment payment) {
            try {
                PaymentGateway.RefundOutcome outcome =
                        // 冪等鍵用付款單號：這條路徑一定是全額退款，
                        // 同一張付款單重試幾次都是同一筆退款
                        paymentGateway.refund(payment, payment.amount(),
                                payment.paymentNo().value());
                if (!outcome.succeeded()) {
                    log.error("退款失敗，將於下一輪重試 paymentNo={}, 原因={}",
                            payment.paymentNo(), outcome.failureReason());
                    metrics.recordRefund(false);
                    return false;
                }
                payment.markRefunded(clock.instant());
                paymentRepository.save(payment);
                metrics.recordRefund(true);
                log.info("已退款 paymentNo={}, 金額={}", payment.paymentNo(), payment.amount());
                return true;
            } catch (RuntimeException e) {
                log.error("退款發生例外，將於下一輪重試 paymentNo={}", payment.paymentNo(), e);
                metrics.recordRefund(false);
                return false;
            }
        }
    }
}
