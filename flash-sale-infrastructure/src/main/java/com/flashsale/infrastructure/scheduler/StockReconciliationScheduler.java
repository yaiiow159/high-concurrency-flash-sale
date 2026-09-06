package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.in.InventoryReconciliationUseCase;
import com.flashsale.application.port.in.StockReconciliationUseCase;
import com.flashsale.application.port.in.dto.ActivityReconciliation;
import com.flashsale.application.port.in.dto.SkuReconciliation;
import com.flashsale.application.port.out.DistributedLock;
import com.flashsale.domain.stock.ReconciliationVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** 庫存對帳排程。 */
@Component
public class StockReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(StockReconciliationScheduler.class);

    private static final String LOCK_KEY = "seckill:lock:reconciliation";
    private static final Duration LOCK_LEASE = Duration.ofMinutes(10);

    private final StockReconciliationUseCase reconciliationUseCase;
    private final InventoryReconciliationUseCase inventoryReconciliationUseCase;
    private final DistributedLock distributedLock;

    public StockReconciliationScheduler(StockReconciliationUseCase reconciliationUseCase,
                                        InventoryReconciliationUseCase inventoryReconciliationUseCase,
                                        DistributedLock distributedLock) {
        this.reconciliationUseCase = reconciliationUseCase;
        this.inventoryReconciliationUseCase = inventoryReconciliationUseCase;
        this.distributedLock = distributedLock;
    }

    /** {@code initialDelay} 刻意設得比其他排程長：應用剛啟動時預熱尚未完成、 MQ 積壓也還沒消化，此時對帳必然報出一堆假偏差，只會製造雜訊。 */
    @Scheduled(
            fixedDelayString = "${flash-sale.reconciliation.interval-ms:600000}",
            initialDelayString = "${flash-sale.reconciliation.initial-delay-ms:120000}")
    public void reconcile() {
        distributedLock.tryExecuteWithLock(LOCK_KEY, LOCK_LEASE, this::runSafely);
    }

    /** 兩種對帳各自獨立 try-catch。 */
    private void runSafely() {
        try {
            List<ActivityReconciliation> results = reconciliationUseCase.reconcileAll();
            summarize(results);
        } catch (RuntimeException e) {
            log.error("秒殺庫存對帳執行失敗，下一輪將重試", e);
        }
        try {
            List<SkuReconciliation> unbalanced = inventoryReconciliationUseCase.reconcileAll();
            unbalanced.forEach(result -> log.error("  {}", result.summary()));
        } catch (RuntimeException e) {
            log.error("一般庫存對帳執行失敗，下一輪將重試", e);
        }
    }

    /** 只在有異常時輸出摘要。 */
    private void summarize(List<ActivityReconciliation> results) {
        List<ActivityReconciliation> problems = results.stream()
                .filter(result -> result.verdict().requiresAttention())
                .toList();

        if (problems.isEmpty()) {
            log.debug("庫存對帳完成：{} 個活動全部帳平", results.size());
            return;
        }

        long oversellRisks = problems.stream()
                .filter(result -> result.verdict() == ReconciliationVerdict.OVERSELL_RISK)
                .count();
        log.error("庫存對帳發現 {} 個活動不平（其中 {} 個有超賣風險），共檢查 {} 個活動",
                problems.size(), oversellRisks, results.size());
        problems.forEach(problem -> log.error("  {}", problem.summary()));
    }
}
