package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.in.SearchIndexReconciliationUseCase;
import com.flashsale.application.port.out.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 搜尋索引對帳排程（ADR-0012）。 */
@Component
public class SearchIndexReconciliationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(SearchIndexReconciliationScheduler.class);

    private static final String LOCK_KEY = "seckill:lock:search-index-reconciliation";
    private static final Duration LOCK_LEASE = Duration.ofMinutes(5);

    private final SearchIndexReconciliationUseCase reconciliationUseCase;
    private final DistributedLock distributedLock;
    private final boolean autoRepair;

    public SearchIndexReconciliationScheduler(
            SearchIndexReconciliationUseCase reconciliationUseCase,
            DistributedLock distributedLock,
            @Value("${flash-sale.search.auto-repair-index:true}") boolean autoRepair) {
        this.reconciliationUseCase = reconciliationUseCase;
        this.distributedLock = distributedLock;
        this.autoRepair = autoRepair;
    }

    /** {@code initialDelay} 設得長：啟動時索引 bootstrap 才剛跑完、 MQ 也還沒追上，此時對帳必然報出一堆假偏差，只會製造雜訊。 */
    @Scheduled(
            fixedDelayString = "${flash-sale.search.reconciliation-interval-ms:900000}",
            initialDelayString = "${flash-sale.search.reconciliation-initial-delay-ms:180000}")
    public void reconcile() {
        distributedLock.tryExecuteWithLock(LOCK_KEY, LOCK_LEASE, this::runSafely);
    }

    /** 吞掉例外。 */
    private void runSafely() {
        try {
            reconciliationUseCase.reconcile(autoRepair);
        } catch (RuntimeException e) {
            log.warn("搜尋索引對帳失敗，下一輪再試", e);
        }
    }
}
