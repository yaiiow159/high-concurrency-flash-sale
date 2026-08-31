package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.in.StockReconciliationUseCase;
import com.flashsale.application.port.in.dto.ActivityReconciliation;
import com.flashsale.application.port.out.DistributedLock;
import com.flashsale.domain.stock.ReconciliationVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 庫存對帳排程。
 *
 * <p><b>這是最終一致系統的體檢機制。</b> 沒有資料庫交易兜底的架構，
 * 偏差不會自癒，只會累積——補償失敗、訊息遺失、人為誤操作，
 * 每一次都在帳上留下一點差額，而且沒有任何東西會主動告訴你。
 *
 * <p>頻率設為 10 分鐘：對帳要掃描 Redis 綁定並查資料庫，成本不低，
 * 跑太密會與正常業務搶資源；但也不能太稀疏，否則偏差累積到被發現時
 * 已經無從追溯是哪一批請求造成的。
 *
 * <p>與其他排程一樣採跨節點互斥，並且<b>絕不讓例外逸出</b>——
 * 排程任務拋出未捕捉例外會被 Spring 直接取消後續排程，
 * 對帳從此靜默停擺，而這正是最不該失去的那道防線。
 */
@Component
public class StockReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(StockReconciliationScheduler.class);

    private static final String LOCK_KEY = "seckill:lock:reconciliation";
    private static final Duration LOCK_LEASE = Duration.ofMinutes(10);

    private final StockReconciliationUseCase reconciliationUseCase;
    private final DistributedLock distributedLock;

    public StockReconciliationScheduler(StockReconciliationUseCase reconciliationUseCase,
                                        DistributedLock distributedLock) {
        this.reconciliationUseCase = reconciliationUseCase;
        this.distributedLock = distributedLock;
    }

    /**
     * {@code initialDelay} 刻意設得比其他排程長：應用剛啟動時預熱尚未完成、
     * MQ 積壓也還沒消化，此時對帳必然報出一堆假偏差，只會製造雜訊。
     */
    @Scheduled(
            fixedDelayString = "${flash-sale.reconciliation.interval-ms:600000}",
            initialDelayString = "${flash-sale.reconciliation.initial-delay-ms:120000}")
    public void reconcile() {
        distributedLock.tryExecuteWithLock(LOCK_KEY, LOCK_LEASE, this::runSafely);
    }

    private void runSafely() {
        try {
            List<ActivityReconciliation> results = reconciliationUseCase.reconcileAll();
            summarize(results);
        } catch (RuntimeException e) {
            log.error("庫存對帳執行失敗，下一輪將重試", e);
        }
    }

    /**
     * 只在有異常時輸出摘要。
     *
     * <p>每 10 分鐘印一次「一切正常」，會讓人在幾天內學會忽略這行日誌——
     * 然後在真的出事那天也一起忽略掉。趨勢觀測交給指標，日誌留給需要行動的事。
     */
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
