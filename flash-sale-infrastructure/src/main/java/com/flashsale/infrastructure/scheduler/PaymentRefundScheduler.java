package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.out.DistributedLock;
import com.flashsale.application.port.out.PaymentGateway;
import com.flashsale.application.port.out.PaymentRepository;
import com.flashsale.application.service.PaymentMetrics;
import com.flashsale.domain.payment.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * 處理待退款的收款。
 *
 * <p>每一筆 {@code REFUND_PENDING} 都代表<b>有一筆錢收了卻沒有訂單對應</b>，
 * 正卡在系統裡。成因只有一個：付款完成的瞬間，逾時關單排程已先一步取消訂單。
 *
 * <p>這個競態無法完全消除，只能保證發生時錢會被退回去。
 * 放著不處理會直接變成客訴與金流爭議——而且是最難解釋的那一種，
 * 因為使用者確實付了錢，訂單卻不存在。
 *
 * <p><b>範圍限制</b>：目前只處理「訂單被關閉」造成的全額退款。
 * 使用者主動申請退貨退款是另一條流程（含審核、部分退款、庫存回補），
 * 屬於 P3 的範圍，需要各自的狀態機與 Saga。
 */
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

    /**
     * 排程方法絕不可讓例外逸出——Spring 會直接取消後續排程，
     * 退款從此靜默停擺，而那些錢會一直卡著。
     */
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

    /**
     * 實際執行退款。
     *
     * <p>拆成獨立 Bean 是為了讓 {@code @Transactional} 真的生效——
     * Spring 交易靠動態代理，同 Bean 內呼叫不會經過代理。
     * 與 {@code OutboxRelayer}、{@code RefreshTokenRevoker} 同一個理由。
     */
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

        /**
         * 單筆失敗不中斷整批——一筆退不掉不該讓其他人的錢也卡著。
         *
         * <p>退款失敗的紀錄會留在 {@code REFUND_PENDING}，下一輪再試；
         * 若持續失敗，{@code payment.refund.total{result="failure"}} 會讓告警抓到。
         */
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
