package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.in.RestockNotificationUseCase;
import com.flashsale.application.port.out.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 掃描補貨並通知等待的人。 */
@Component
public class RestockNotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(RestockNotificationScheduler.class);

    private static final String LOCK_KEY = "seckill:lock:restock-notify";
    private static final Duration LOCK_LEASE = Duration.ofMinutes(5);

    private final RestockNotificationUseCase restockNotification;
    private final DistributedLock distributedLock;

    public RestockNotificationScheduler(RestockNotificationUseCase restockNotification,
                                        DistributedLock distributedLock) {
        this.restockNotification = restockNotification;
        this.distributedLock = distributedLock;
    }

    /**
     * 跨節點互斥是必要的（CLAUDE.md 鐵則 6-1）：兩個節點同時跑會讓同一批人收到兩則通知。
     *
     * <p>條件式 UPDATE 其實已經擋住重複，但那是最後一道；先用鎖避免兩邊都做白工。
     */
    @Scheduled(
            fixedDelayString = "${flash-sale.restock.notify-interval-ms:60000}",
            initialDelayString = "${flash-sale.restock.notify-initial-delay-ms:90000}")
    public void notifyRestocked() {
        distributedLock.tryExecuteWithLock(LOCK_KEY, LOCK_LEASE, this::runSafely);
    }

    private void runSafely() {
        try {
            int notified = restockNotification.notifyAllRestocked();
            if (notified > 0) {
                log.info("到貨通知：本輪通知 {} 人", notified);
            }
        } catch (RuntimeException e) {
            log.error("到貨通知掃描失敗，本輪略過", e);
        }
    }
}
