package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.out.EngagementRepository;
import com.flashsale.application.port.out.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 清掉過舊的瀏覽紀錄。
 *
 * <p>沒有這個的話 {@code browsing_history} 只增不減，而它是
 * 「看了又看」那個自連接的乘數——一個逛了兩年的重度使用者會帶著幾千列
 * 進入 join，五百個這樣的人就是數百萬列的中間結果。
 *
 * <p>放排程而不是在 {@code recordView} 裡順手修剪：那支是每次商品頁瀏覽
 * 都會打的，不該為了清理而變貴。代價是上限是軟的，短時間內仍可能超出。
 */
@Component
public class BrowsingHistoryCleanupScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(BrowsingHistoryCleanupScheduler.class);

    private static final String LOCK_KEY = "seckill:lock:history-cleanup";
    private static final Duration LOCK_LEASE = Duration.ofMinutes(10);

    private final EngagementRepository engagementRepository;
    private final DistributedLock distributedLock;

    public BrowsingHistoryCleanupScheduler(EngagementRepository engagementRepository,
                                           DistributedLock distributedLock) {
        this.engagementRepository = engagementRepository;
        this.distributedLock = distributedLock;
    }

    @Scheduled(cron = "${flash-sale.engagement.history-cleanup-cron:0 45 4 * * *}")
    public void cleanup() {
        distributedLock.tryExecuteWithLock(LOCK_KEY, LOCK_LEASE, this::runSafely);
    }

    private void runSafely() {
        try {
            int removed = engagementRepository.purgeOldViews();
            if (removed > 0) {
                log.info("清理瀏覽紀錄：刪除 {} 列", removed);
            }
        } catch (RuntimeException e) {
            log.error("瀏覽紀錄清理失敗，本輪略過", e);
        }
    }
}
