package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.in.StockReleaseUseCase;
import com.flashsale.application.port.out.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 庫存釋放排程。 */
@Component
public class StockReleaseScheduler {

    private static final Logger log = LoggerFactory.getLogger(StockReleaseScheduler.class);

    private static final String LOCK_KEY = "seckill:lock:release-scan";
    private static final Duration LOCK_LEASE = Duration.ofMinutes(5);

    private final StockReleaseUseCase stockReleaseUseCase;
    private final DistributedLock distributedLock;

    public StockReleaseScheduler(StockReleaseUseCase stockReleaseUseCase,
                                 DistributedLock distributedLock) {
        this.stockReleaseUseCase = stockReleaseUseCase;
        this.distributedLock = distributedLock;
    }

    /** {@code initialDelay} 給得長，理由與對帳排程相同： 應用剛啟動時預熱可能還沒跑完，此時去釋放會與劃撥搶同一列。 */
    @Scheduled(
            fixedDelayString = "${flash-sale.inventory.release-interval-ms:1800000}",
            initialDelayString = "${flash-sale.inventory.release-initial-delay-ms:180000}")
    public void release() {
        distributedLock.tryExecuteWithLock(LOCK_KEY, LOCK_LEASE, this::runSafely);
    }

    private void runSafely() {
        try {
            stockReleaseUseCase.releaseEndedActivities();
        } catch (RuntimeException e) {
            log.error("庫存釋放排程執行失敗，本輪略過", e);
        }
    }
}
