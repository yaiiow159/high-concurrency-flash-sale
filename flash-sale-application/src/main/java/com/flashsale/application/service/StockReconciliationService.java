package com.flashsale.application.service;

import com.flashsale.application.config.ReconciliationPolicy;
import com.flashsale.application.config.SeckillPolicy;
import com.flashsale.application.port.in.StockReconciliationUseCase;
import com.flashsale.application.port.in.dto.ActivityReconciliation;
import com.flashsale.application.port.out.ActivityRepository;
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
 * <p>核對恆等式：{@code Redis 餘量 + Σ(未取消訂單數量) = 活動總庫存}。
 *
 * <p><b>偵測與修復刻意分離，且修復預設關閉。</b>
 * 理由是「自動修復」與「自動破壞」之間只隔著一個 bug：
 * 若對帳邏輯本身算錯，自動修復會拿著錯誤的結論去改動正確的資料，
 * 造成比它要修的問題嚴重得多的後果。
 *
 * <p>因此只有<b>能被證明安全</b>的那一類偏差才納入自動修復：
 * 孤兒扣減（庫存已扣、訂單不存在、且已超過寬限期）。
 * 這類紀錄有明確的判定依據，且修復方向只會「歸還」庫存，不會憑空製造可賣量。
 *
 * <p>反方向的偏差（{@code OVERSELL_RISK}）一律不自動處理——
 * 下修餘量會讓正在進行中的合法請求無故失敗，必須由人判斷。
 */
@Service
public class StockReconciliationService implements StockReconciliationUseCase {

    private static final Logger log = LoggerFactory.getLogger(StockReconciliationService.class);

    private final ActivityRepository activityRepository;
    private final OrderRepository orderRepository;
    private final StockRepository stockRepository;
    private final OrderNoGenerator orderNoGenerator;
    private final SoldOutMarker soldOutMarker;
    private final SeckillMetrics metrics;
    private final ReconciliationPolicy policy;
    private final SeckillPolicy seckillPolicy;
    private final Clock clock;

    public StockReconciliationService(ActivityRepository activityRepository,
                                      OrderRepository orderRepository,
                                      StockRepository stockRepository,
                                      OrderNoGenerator orderNoGenerator,
                                      SoldOutMarker soldOutMarker,
                                      SeckillMetrics metrics,
                                      ReconciliationPolicy policy,
                                      SeckillPolicy seckillPolicy,
                                      Clock clock) {
        this.activityRepository = activityRepository;
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
                orphans.detected(), orphans.repaired());

        report(result);
        return result;
    }

    /**
     * 掃描並（視設定）修復孤兒扣減。
     *
     * <p>判定一筆綁定是孤兒需要<b>同時</b>滿足兩個條件：
     * <ol>
     *   <li>資料庫查無此訂單號</li>
     *   <li>訂單號的產生時間已超過寬限期</li>
     * </ol>
     *
     * <p>第二個條件不可省略。剛產生幾秒的訂單很可能只是還在 MQ 佇列裡排隊，
     * 此時退庫，等訊息真的被消費時訂單仍會建立——<b>庫存退了但訂單還在，就是超賣</b>。
     */
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

    /**
     * 輸出對帳結果。
     *
     * <p>帳平時只記 debug——對帳每十分鐘跑一次，全部記 info 會把日誌淹掉，
     * 真正的異常反而被埋起來。趨勢觀測交給指標，日誌只留給需要人看的事。
     */
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
