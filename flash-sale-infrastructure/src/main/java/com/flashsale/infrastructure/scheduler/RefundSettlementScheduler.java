package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.in.RefundRecoveryUseCase;
import com.flashsale.application.port.out.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 補送卡住的退款（ADR-0031）。
 *
 * <p>退款訊息的重試預算只有幾秒，用完就進死信而死信沒有補送路徑——
 * 退貨單早已 commit 成已核可，於是帳上說退了、錢沒出去。這個排程讓
 * 「錢最終有沒有送到」不再取決於佇列的重試次數。
 */
@Component
public class RefundSettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefundSettlementScheduler.class);

    private static final String LOCK_KEY = "seckill:lock:refund-settlement";
    private static final Duration LOCK_LEASE = Duration.ofMinutes(5);
    private static final int BATCH_SIZE = 50;

    private final RefundRecoveryUseCase refundRecovery;
    private final DistributedLock distributedLock;

    /**
     * 發起後多久還沒到帳才算卡住。**必須明顯長於消費端的重試預算**，
     * 否則排程會與還在重試的消費端同時呼叫閘道。
     */
    private final Duration settlementGrace;

    public RefundSettlementScheduler(
            RefundRecoveryUseCase refundRecovery,
            DistributedLock distributedLock,
            @Value("${flash-sale.aftersales.refund-settlement-grace-ms:300000}") long graceMillis) {
        this.refundRecovery = refundRecovery;
        this.distributedLock = distributedLock;
        this.settlementGrace = Duration.ofMillis(graceMillis);
    }

    // 跨節點互斥：兩個節點同時推同一筆會對閘道發出兩次請求。閘道的冪等鍵擋得住，
    // 但那是最後一道，不該當成第一道用（鐵則 6-1）
    @Scheduled(fixedDelayString = "${flash-sale.aftersales.refund-recovery-interval-ms:60000}")
    public void recoverStuckRefunds() {
        distributedLock.tryExecuteWithLock(LOCK_KEY, LOCK_LEASE, this::runSafely);
    }

    /** 排程方法絕不可讓例外逸出——Spring 會取消後續排程，補送從此靜默停擺。 */
    private void runSafely() {
        try {
            refundRecovery.recoverStuckRefunds(settlementGrace, BATCH_SIZE);
        } catch (RuntimeException e) {
            log.error("補送退款失敗，下一輪將重試", e);
        }
    }
}
