package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.out.DistributedLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Outbox 中繼排程的觸發器。
 *
 * <p><b>與 {@link OutboxRelayer} 刻意拆成兩個 Bean</b>：Spring 的交易是動態代理，
 * 同一個 Bean 內部呼叫不會經過代理，{@code @Transactional} 會安靜失效。
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
     * {@code fixedDelay} 而非 {@code fixedRate}：後者在某輪超時後會堆疊，
     * 並在下游恢復的瞬間同時湧出。
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
