package com.flashsale.application.service;

import com.flashsale.application.config.SeckillPolicy;
import com.flashsale.application.port.in.StockWarmupUseCase;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.DistributedLock;
import com.flashsale.application.port.out.InventoryRepository;
import com.flashsale.application.port.out.OrderRepository;
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
 * <p>寫進 Redis 的是<b>總庫存 − 已售出</b>，不是總庫存。直接寫總量的話，
 * Redis 一重啟就把賣掉的量抹掉，同一批貨再賣一次。{@code force=true} 也走同一條路。
 */
@Service
public class StockWarmupService implements StockWarmupUseCase {

    private static final Logger log = LoggerFactory.getLogger(StockWarmupService.class);
    private static final String LOCK_PREFIX = "seckill:lock:warmup:";
    private static final Duration LOCK_WAIT = Duration.ofSeconds(3);
    private static final Duration LOCK_LEASE = Duration.ofSeconds(10);

    private final ActivityRepository activityRepository;
    private final OrderRepository orderRepository;
    private final StockRepository stockRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryAllocator inventoryAllocator;
    private final DistributedLock distributedLock;
    private final SoldOutMarker soldOutMarker;
    private final SeckillPolicy policy;
    private final Clock clock;

    public StockWarmupService(ActivityRepository activityRepository,
                              OrderRepository orderRepository,
                              StockRepository stockRepository,
                              InventoryRepository inventoryRepository,
                              InventoryAllocator inventoryAllocator,
                              DistributedLock distributedLock,
                              SoldOutMarker soldOutMarker,
                              SeckillPolicy policy,
                              Clock clock) {
        this.activityRepository = activityRepository;
        this.orderRepository = orderRepository;
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

    /** 劃撥 + 預熱。 */
    private long doWarmUp(SeckillActivity activity, boolean force) {
        requireNotAlreadyReleased(activity);

        inventoryAllocator.allocate(activity.id(), activity.skuId(),
                activity.totalStock(), clock.instant());

        Duration ttl = calculateTtl(activity, clock.instant());
        stockRepository.initialize(activity.id(), remainingStockOf(activity), ttl, force);
        soldOutMarker.clear(activity.id());

        long available = stockRepository.availableStock(activity.id());
        log.info("活動 {} 預熱完成：可用庫存={}, TTL={}", activity.id(), available, ttl);
        return available;
    }

    /** 依訂單重建應有的餘量，而不是直接寫入總庫存。 */
    private int remainingStockOf(SeckillActivity activity) {
        long sold = orderRepository.sumActiveQuantity(activity.id());
        long remaining = activity.totalStock() - sold;
        if (remaining < 0) {
            log.error("活動 {} 的已售出量 {} 超過總庫存 {}，預熱以 0 寫入並等待人工處理",
                    activity.id(), sold, activity.totalStock());
            return 0;
        }
        if (sold > 0) {
            log.info("活動 {} 依訂單重建餘量：總庫存 {} - 已售出 {} = {}",
                    activity.id(), activity.totalStock(), sold, remaining);
        }
        return (int) remaining;
    }

    /** 擋住「釋放後又重新預熱」。 */
    private void requireNotAlreadyReleased(SeckillActivity activity) {
        if (inventoryRepository.isReleased(activity.id(), activity.skuId())) {
            throw new BusinessException(ErrorCode.ACTIVITY_STOCK_ALREADY_RELEASED,
                    "活動 %d 的庫存已釋放回可售池，不可重新預熱".formatted(activity.id()));
        }
    }

    /** TTL = 距離活動結束的時間 + 緩衝。 */
    private Duration calculateTtl(SeckillActivity activity, Instant now) {
        Duration untilEnd = Duration.between(now, activity.period().endAt());
        Duration effective = untilEnd.isNegative() ? Duration.ZERO : untilEnd;
        return effective.plus(policy.stockKeyTtlBuffer());
    }
}
