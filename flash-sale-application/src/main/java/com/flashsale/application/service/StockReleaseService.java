package com.flashsale.application.service;

import com.flashsale.application.config.SeckillPolicy;
import com.flashsale.application.port.in.StockReleaseUseCase;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.DistributedLock;
import com.flashsale.application.port.out.InventoryRepository;
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
import java.util.Optional;

/** 活動結束後的庫存釋放。 */
@Service
public class StockReleaseService implements StockReleaseUseCase {

    private static final Logger log = LoggerFactory.getLogger(StockReleaseService.class);
    private static final String LOCK_PREFIX = "seckill:lock:release:";
    private static final Duration LOCK_WAIT = Duration.ofSeconds(3);
    private static final Duration LOCK_LEASE = Duration.ofSeconds(30);

    private final ActivityRepository activityRepository;
    private final StockRepository stockRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryAllocator inventoryAllocator;
    private final DistributedLock distributedLock;
    private final SeckillPolicy policy;
    private final Clock clock;

    public StockReleaseService(ActivityRepository activityRepository,
                               StockRepository stockRepository,
                               InventoryRepository inventoryRepository,
                               InventoryAllocator inventoryAllocator,
                               DistributedLock distributedLock,
                               SeckillPolicy policy,
                               Clock clock) {
        this.activityRepository = activityRepository;
        this.stockRepository = stockRepository;
        this.inventoryRepository = inventoryRepository;
        this.inventoryAllocator = inventoryAllocator;
        this.distributedLock = distributedLock;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    public int releaseEndedActivities() {
        // 只處理「結束時間 + 緩衝期」都已經過去的活動
        Instant releasableBefore = clock.instant().minus(policy.stockKeyTtlBuffer());
        List<SeckillActivity> candidates = activityRepository.findEndedBefore(releasableBefore);

        int released = 0;
        for (SeckillActivity activity : candidates) {
            try {
                if (release(activity.id())) {
                    released++;
                }
            } catch (RuntimeException e) {
                // 單一活動釋放失敗不中斷整輪；下一輪會再試，
                // 而流水的唯一索引保證重試不會重複歸還。
                log.error("活動 {} 庫存釋放失敗，繼續處理其餘活動", activity.id(), e);
            }
        }
        if (released > 0) {
            log.info("庫存釋放完成：{}/{} 場活動", released, candidates.size());
        }
        return released;
    }

    @Override
    public boolean release(Long activityId) {
        return distributedLock.executeWithLock(
                LOCK_PREFIX + activityId, LOCK_WAIT, LOCK_LEASE,
                () -> doRelease(activityId));
    }

    /** 確認活動已結束且過了緩衝期。 */
    private void requireCooledDown(SeckillActivity activity) {
        Instant cooledDownAt = activity.period().endAt().plus(policy.stockKeyTtlBuffer());
        if (clock.instant().isBefore(cooledDownAt)) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_COOLED_DOWN,
                    "活動 %d 要到 %s 之後才可釋放庫存".formatted(activity.id(), cooledDownAt));
        }
    }

    /** 讀 Redis 剩餘量 → 更新 MySQL → 丟棄 Redis 鍵。 */
    private boolean doRelease(Long activityId) {
        Optional<SeckillActivity> found = activityRepository.findById(activityId);
        if (found.isEmpty()) {
            return false;
        }
        SeckillActivity activity = found.get();
        requireCooledDown(activity);

        Optional<Integer> allocated =
                inventoryRepository.findAllocatedQuantity(activityId, activity.skuId());
        if (allocated.isEmpty()) {
            // 沒有劃撥紀錄代表這場活動是舊資料（V7 之前建立），沒有東西可釋放
            log.debug("活動 {} 無劃撥紀錄，略過釋放", activityId);
            return false;
        }

        long remaining = stockRepository.availableStock(activityId);
        if (remaining < 0) {
            // 鍵已因 TTL 過期。剩餘量無從得知，此時猜測等於編造數字——
            // 一律當成全數售出（最保守），差額留給對帳以流水追查。
            log.warn("活動 {} 的庫存鍵已過期，無法得知剩餘量，以全數售出處理", activityId);
            remaining = 0;
        }

        boolean done = inventoryAllocator.release(activityId, activity.skuId(),
                allocated.get(), (int) remaining, clock.instant());
        if (done) {
            stockRepository.discard(activityId);
        }
        return done;
    }
}
