package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.in.SearchIndexReconciliationUseCase;
import com.flashsale.application.port.out.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 搜尋索引對帳排程（ADR-0012）。
 *
 * <p>索引與資料庫分岔<b>完全靜默</b>——搜尋照樣回 200，只是結果是錯的。
 * 這個排程是唯一會主動發現它的東西。
 *
 * <p><b>自動修復預設開啟，與庫存對帳相反。</b>
 * 理由不是搜尋比較不重要，而是修復動作的性質不同：這裡的「修復」
 * 與那個還沒被消費的事件會做的事一模一樣（讀當下狀態、上架就寫、否則移除），
 * 提早做一次不會產生任何新的狀態。庫存那邊的「退庫」則是一個新的決定，
 * 可能與還在佇列裡的請求衝突。詳見 {@code SearchIndexReconciliationService}。
 */
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

    /**
     * {@code initialDelay} 設得長：啟動時索引 bootstrap 才剛跑完、
     * MQ 也還沒追上，此時對帳必然報出一堆假偏差，只會製造雜訊。
     */
    @Scheduled(
            fixedDelayString = "${flash-sale.search.reconciliation-interval-ms:900000}",
            initialDelayString = "${flash-sale.search.reconciliation-initial-delay-ms:180000}")
    public void reconcile() {
        distributedLock.tryExecuteWithLock(LOCK_KEY, LOCK_LEASE, this::runSafely);
    }

    /**
     * 吞掉例外。
     *
     * <p>Spring 的 {@code @Scheduled} 在方法拋出例外時<b>不會停掉排程</b>，
     * 但 ES 掛著的期間每一輪都會拋，日誌會被塞滿而真正的錯誤淹沒其中。
     * 記一行 warn 就夠——ES 掛掉這件事有它自己的健康檢查在看。
     */
    private void runSafely() {
        try {
            reconciliationUseCase.reconcile(autoRepair);
        } catch (RuntimeException e) {
            log.warn("搜尋索引對帳失敗，下一輪再試", e);
        }
    }
}
