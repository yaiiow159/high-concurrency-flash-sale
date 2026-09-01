package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.in.StockReleaseUseCase;
import com.flashsale.application.port.out.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 庫存釋放排程。
 *
 * <p>把活動結束後沒賣完的量歸還可售池。少了這一步，
 * 每辦一場活動就有一批貨永遠卡在 {@code allocated} 上——帳面看得到、實際賣不掉。
 *
 * <p>頻率設為 30 分鐘：釋放的時機由「活動結束 + 緩衝期」決定，
 * 早跑一輪也不會提前釋放任何東西，因此不需要跑得密。
 * 相對地，它每次都要掃全表找已結束的活動，跑太密純粹是浪費。
 *
 * <p>與其他排程一樣採跨節點互斥並吞掉所有例外——
 * 排程拋出未捕捉例外會被 Spring 取消後續排程，
 * 那會讓釋放靜默停擺，而卡住的庫存不會有任何告警。
 */
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

    /**
     * {@code initialDelay} 給得長，理由與對帳排程相同：
     * 應用剛啟動時預熱可能還沒跑完，此時去釋放會與劃撥搶同一列。
     */
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
