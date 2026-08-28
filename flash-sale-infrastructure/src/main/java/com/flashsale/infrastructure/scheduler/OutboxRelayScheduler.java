package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.out.DistributedLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Outbox 中繼排程的觸發器。
 *
 * <p>只負責兩件事：定時觸發、跨節點互斥。實際邏輯在 {@link OutboxRelayer}
 * （分開的原因見該類別的說明）。
 *
 * <p><b>多節點互斥</b>採 {@code tryLock} 而非阻塞等待：取不到鎖代表別的節點正在搬，
 * 這一輪跳過即可，下一輪還會再來。阻塞等待只會讓排程執行緒堆積。
 */
@Component
public class OutboxRelayScheduler {

    private static final String RELAY_LOCK = "seckill:lock:outbox-relay";
    private static final String CLEANUP_LOCK = "seckill:lock:outbox-cleanup";
    private static final Duration RELAY_LEASE = Duration.ofSeconds(30);
    private static final Duration CLEANUP_LEASE = Duration.ofMinutes(2);

    private final OutboxRelayer relayer;
    private final DistributedLock distributedLock;

    public OutboxRelayScheduler(OutboxRelayer relayer, DistributedLock distributedLock) {
        this.relayer = relayer;
        this.distributedLock = distributedLock;
    }

    /**
     * {@code fixedDelay} 而非 {@code fixedRate}：前者從「上次結束」起算，後者從「上次開始」起算。
     * 用 fixedRate 時，一旦某輪執行超過間隔，排程會開始堆疊，
     * 並在下游恢復的瞬間同時湧出——這是把小故障放大成大故障的經典模式。
     */
    @Scheduled(fixedDelayString = "${flash-sale.outbox.relay-interval-ms:1000}")
    public void relay() {
        distributedLock.tryExecuteWithLock(RELAY_LOCK, RELAY_LEASE, relayer::relayPendingEvents);
    }

    @Scheduled(cron = "${flash-sale.outbox.cleanup-cron:0 30 3 * * *}")
    public void cleanup() {
        distributedLock.tryExecuteWithLock(CLEANUP_LOCK, CLEANUP_LEASE, relayer::deleteOldPublishedEvents);
    }
}
