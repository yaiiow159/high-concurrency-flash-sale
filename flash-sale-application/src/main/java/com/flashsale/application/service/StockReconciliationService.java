package com.flashsale.application.service;

import com.flashsale.application.config.ReconciliationPolicy;
import com.flashsale.application.config.SeckillPolicy;
import com.flashsale.application.port.in.StockReconciliationUseCase;
import com.flashsale.application.port.in.dto.ActivityReconciliation;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.InventoryRepository;
import com.flashsale.application.port.out.OrderNoGenerator;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.SoldOutMarker;
import com.flashsale.application.port.out.StockRepository;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.domain.stock.StockBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 庫存對帳服務。
 *
 * <p><b>自動修復預設關閉</b>，且只處理能被證明安全的偏差：孤兒扣減且已過寬限期。
 * {@code OVERSELL_RISK} 方向一律人工——下修餘量會讓進行中的合法請求無故失敗。
 */
@Service
public class StockReconciliationService implements StockReconciliationUseCase {

    private static final Logger log = LoggerFactory.getLogger(StockReconciliationService.class);

    private final ActivityRepository activityRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final StockRepository stockRepository;
    private final OrderNoGenerator orderNoGenerator;
    private final SoldOutMarker soldOutMarker;
    private final SeckillMetrics metrics;
    private final ReconciliationPolicy policy;
    private final SeckillPolicy seckillPolicy;
    private final Clock clock;

    public StockReconciliationService(ActivityRepository activityRepository,
                                      InventoryRepository inventoryRepository,
                                      OrderRepository orderRepository,
                                      StockRepository stockRepository,
                                      OrderNoGenerator orderNoGenerator,
                                      SoldOutMarker soldOutMarker,
                                      SeckillMetrics metrics,
                                      ReconciliationPolicy policy,
                                      SeckillPolicy seckillPolicy,
                                      Clock clock) {
        this.activityRepository = activityRepository;
        this.inventoryRepository = inventoryRepository;
        this.orderRepository = orderRepository;
        this.stockRepository = stockRepository;
        this.orderNoGenerator = orderNoGenerator;
        this.soldOutMarker = soldOutMarker;
        this.metrics = metrics;
        this.policy = policy;
        this.seckillPolicy = seckillPolicy;
        this.clock = clock;
    }

    @Override
    public List<ActivityReconciliation> reconcileAll() {
        // 含剛結束的活動：庫存洩漏最常在活動尾聲浮現，只查進行中的會系統性漏掉。
        Instant endedAfter = clock.instant().minus(seckillPolicy.stockKeyTtlBuffer());
        List<SeckillActivity> activities = activityRepository.findForReconciliation(endedAfter);

        List<ActivityReconciliation> results = new ArrayList<>(activities.size());
        for (SeckillActivity activity : activities) {
            try {
                results.add(reconcileActivity(activity));
            } catch (RuntimeException e) {
                // 單一活動對帳失敗不中斷整輪——其他活動的偏差同樣需要被發現。
                log.error("活動 {} 對帳失敗，繼續處理其餘活動", activity.id(), e);
            }
        }
        return results;
    }

    @Override
    public ActivityReconciliation reconcile(Long activityId) {
        SeckillActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND));
        return reconcileActivity(activity);
    }

    private ActivityReconciliation reconcileActivity(SeckillActivity activity) {
        long actualAvailable = stockRepository.availableStock(activity.id());
        if (actualAvailable < 0) {
            // 未預熱不是異常：活動可能剛建立，或早已結束而鍵已過期。
            return ActivityReconciliation.notInitialized(activity.id(), activity.totalStock());
        }

        long activeOrderQuantity = orderRepository.sumActiveQuantity(activity.id());
        OrphanOutcome orphans = handleOrphanBindings(activity.id());

        ActivityReconciliation result = ActivityReconciliation.of(
                activity.id(), activity.totalStock(), activeOrderQuantity,
                // 退回孤兒扣減後餘量已改變，重讀一次才不會回報一個剛被自己修掉的偏差
                orphans.repaired() > 0 ? stockRepository.availableStock(activity.id()) : actualAvailable,
                orphans.detected(), orphans.repaired(),
                isStockUnbacked(activity));

        report(result);
        return result;
    }

    /** Redis 有庫存，但 MySQL 沒有對應的劃撥額度撐著。 */
    private boolean isStockUnbacked(SeckillActivity activity) {
        return inventoryRepository.isReleased(activity.id(), activity.skuId());
    }

    /** 掃描並（視設定）修復孤兒扣減。 */
    private OrphanOutcome handleOrphanBindings(Long activityId) {
        Instant orphanThreshold = clock.instant().minus(policy.orphanGracePeriod());
        AtomicInteger detected = new AtomicInteger();
        AtomicInteger repaired = new AtomicInteger();

        stockRepository.scanBindings(activityId, policy.scanBatchSize(), batch -> {
            List<StockBinding> candidates = filterAgedBindings(batch, orphanThreshold);
            if (candidates.isEmpty()) {
                return;
            }
            // 批次查詢存在性：逐筆 exists 在數十萬筆綁定下就是數十萬次往返。
            Set<String> existing = orderRepository.findExistingOrderNos(
                    candidates.stream().map(StockBinding::orderNo).toList());

            for (StockBinding binding : candidates) {
                if (existing.contains(binding.orderNo())) {
                    continue;
                }
                detected.incrementAndGet();
                if (repairOrphan(activityId, binding)) {
                    repaired.incrementAndGet();
                }
            }
        });

        return new OrphanOutcome(detected.get(), repaired.get());
    }

    /** 濾掉還在寬限期內的綁定；無法解析產生時間的一律保守略過，不冒險退庫。 */
    private List<StockBinding> filterAgedBindings(List<StockBinding> batch, Instant threshold) {
        return batch.stream()
                .filter(binding -> orderNoGenerator.issuedAt(OrderNo.of(binding.orderNo()))
                        .map(issuedAt -> issuedAt.isBefore(threshold))
                        .orElse(false))
                .toList();
    }

    private boolean repairOrphan(Long activityId, StockBinding binding) {
        if (!policy.autoRepairOrphans()) {
            log.warn("偵測到孤兒扣減（自動修復未啟用）activityId={}, requestId={}, orderNo={}, 數量={}",
                    activityId, binding.requestId(), binding.orderNo(), binding.quantity());
            metrics.recordOrphanBinding(activityId, "detected");
            return false;
        }
        if (!binding.isReversible()) {
            // 舊格式憑證只記了訂單號，沒有數量。硬退會退錯數字，
            // 那比放著不動更糟——寧可留給人工處理。
            log.error("孤兒扣減缺少數量資訊，無法安全退庫，需人工處理 activityId={}, requestId={}",
                    activityId, binding.requestId());
            metrics.recordOrphanBinding(activityId, "not-reversible");
            return false;
        }
        try {
            boolean restored = stockRepository.restore(
                    activityId, binding.userId(), binding.quantity(), binding.requestId());
            if (restored) {
                soldOutMarker.clear(activityId);
                log.info("已退回孤兒扣減 activityId={}, requestId={}, 數量={}",
                        activityId, binding.requestId(), binding.quantity());
            }
            metrics.recordOrphanBinding(activityId, restored ? "repaired" : "already-released");
            return restored;
        } catch (RuntimeException e) {
            log.error("退回孤兒扣減失敗 activityId={}, requestId={}", activityId, binding.requestId(), e);
            metrics.recordOrphanBinding(activityId, "repair-failed");
            return false;
        }
    }

    /** 輸出對帳結果。 */
    private void report(ActivityReconciliation result) {
        metrics.recordReconciliation(result);
        if (result.verdict().requiresAttention()) {
            log.error("庫存對帳不平：{}", result.summary());
        } else {
            log.debug("庫存對帳：{}", result.summary());
        }
    }

    private record OrphanOutcome(int detected, int repaired) {
    }
}
