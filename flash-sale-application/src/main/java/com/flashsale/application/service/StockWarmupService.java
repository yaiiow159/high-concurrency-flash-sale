package com.flashsale.application.service;

import com.flashsale.application.config.SeckillPolicy;
import com.flashsale.application.port.in.StockWarmupUseCase;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.DistributedLock;
import com.flashsale.application.port.out.InventoryRepository;
import com.flashsale.application.port.out.SoldOutMarker;
import com.flashsale.application.port.out.StockRepository;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 庫存預熱服務。
 *
 * <p><b>這裡是分散式鎖真正該出現的地方</b>：多台機器同時啟動、或營運同時點兩次預熱按鈕，
 * 若無互斥就可能把庫存重複寫入。與扣減不同，預熱是低頻操作，用鎖串行化的代價可以忽略。
 *
 * <p>{@code force=false} 時只在鍵不存在才寫入（Redis SET NX），這是第二道保險：
 * 即使鎖因為節點時鐘漂移而失效，也不會把已售出的量重新加回去。
 * 分散式鎖從來不該是唯一的正確性依據。
 */
@Service
public class StockWarmupService implements StockWarmupUseCase {

    private static final Logger log = LoggerFactory.getLogger(StockWarmupService.class);
    private static final String LOCK_PREFIX = "seckill:lock:warmup:";
    private static final Duration LOCK_WAIT = Duration.ofSeconds(3);
    private static final Duration LOCK_LEASE = Duration.ofSeconds(10);

    private final ActivityRepository activityRepository;
    private final StockRepository stockRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryAllocator inventoryAllocator;
    private final DistributedLock distributedLock;
    private final SoldOutMarker soldOutMarker;
    private final SeckillPolicy policy;
    private final Clock clock;

    public StockWarmupService(ActivityRepository activityRepository,
                              StockRepository stockRepository,
                              InventoryRepository inventoryRepository,
                              InventoryAllocator inventoryAllocator,
                              DistributedLock distributedLock,
                              SoldOutMarker soldOutMarker,
                              SeckillPolicy policy,
                              Clock clock) {
        this.activityRepository = activityRepository;
        this.stockRepository = stockRepository;
        this.inventoryRepository = inventoryRepository;
        this.inventoryAllocator = inventoryAllocator;
        this.distributedLock = distributedLock;
        this.soldOutMarker = soldOutMarker;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    public long warmUp(Long activityId, boolean force) {
        SeckillActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND));
        return distributedLock.executeWithLock(
                LOCK_PREFIX + activityId, LOCK_WAIT, LOCK_LEASE,
                () -> doWarmUp(activity, force));
    }

    @Override
    public int warmUpAllOnline() {
        List<SeckillActivity> activities = activityRepository.findOnlineActivities();
        int warmed = 0;
        for (SeckillActivity activity : activities) {
            try {
                warmUp(activity.id(), false);
                warmed++;
            } catch (RuntimeException e) {
                // 單一活動預熱失敗不該讓整個應用起不來，其餘活動仍應正常開賣。
                log.error("活動 {} 預熱失敗，其餘活動繼續", activity.id(), e);
            }
        }
        log.info("批次預熱完成：{}/{} 個活動", warmed, activities.size());
        return warmed;
    }

    /**
     * 劃撥 + 預熱。
     *
     * <p><b>順序不可對調：先動 MySQL，再寫 Redis。</b>
     * 兩者無法原子化（ADR-0008 已接受這個代價），因此順序決定了失敗時往哪邊倒：
     *
     * <ul>
     *   <li>MySQL 成功、Redis 失敗 → 可售量已扣但 Redis 沒有額度，
     *       表現為<b>少賣</b>，由對帳發現（{@code allocated > 0} 卻無對應鍵）</li>
     *   <li>反過來先寫 Redis → Redis 有額度但可售量沒扣，
     *       同一批貨被兩條通道各賣一次，就是<b>超賣</b></li>
     * </ul>
     *
     * <p>少賣可以事後補救，超賣不能。這與整個系統對 Redis 故障採 fail-closed
     * 是同一條原則：選擇代價較小的失敗方向。
     */
    private long doWarmUp(SeckillActivity activity, boolean force) {
        requireNotAlreadyReleased(activity);

        inventoryAllocator.allocate(activity.id(), activity.skuId(),
                activity.totalStock(), clock.instant());

        Duration ttl = calculateTtl(activity, clock.instant());
        stockRepository.initialize(activity.id(), activity.totalStock(), ttl, force);
        soldOutMarker.clear(activity.id());

        long available = stockRepository.availableStock(activity.id());
        log.info("活動 {} 預熱完成：可用庫存={}, TTL={}", activity.id(), available, ttl);
        return available;
    }

    /**
     * 擋住「釋放後又重新預熱」。
     *
     * <p>這是雙模型最隱蔽的一種超賣：劃撥流水的唯一索引會讓重新劃撥被安靜略過
     * （視為冪等），但 {@code stockRepository.initialize} 不受那道索引管，
     * 它會照樣把庫存寫回 Redis。結果是 Redis 有一批可賣的量，
     * 而 MySQL 的 {@code available} 從沒為它付過帳——同一批貨賣了兩次。
     *
     * <p>要重新開賣同一批商品，正確做法是建立新活動，
     * 讓它走完整的劃撥流程；而不是把一場已結算的活動叫醒。
     */
    private void requireNotAlreadyReleased(SeckillActivity activity) {
        if (inventoryRepository.isReleased(activity.id(), activity.skuId())) {
            throw new BusinessException(ErrorCode.ACTIVITY_STOCK_ALREADY_RELEASED,
                    "活動 %d 的庫存已釋放回可售池，不可重新預熱".formatted(activity.id()));
        }
    }

    /**
     * TTL = 距離活動結束的時間 + 緩衝。
     *
     * <p>緩衝是為了讓活動結束後仍在跑的補償流程有鍵可退——
     * 若鍵在活動結束當下就消失，補償退回的庫存會寫進一個沒人看的新鍵。
     */
    private Duration calculateTtl(SeckillActivity activity, Instant now) {
        Duration untilEnd = Duration.between(now, activity.period().endAt());
        Duration effective = untilEnd.isNegative() ? Duration.ZERO : untilEnd;
        return effective.plus(policy.stockKeyTtlBuffer());
    }
}
