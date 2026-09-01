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

/**
 * 活動結束後的庫存釋放。
 *
 * <p>把秒殺沒賣完的量歸還可售池，讓那些貨可以繼續正常銷售。
 * 少了這一步，每辦一場活動就有一批庫存永遠卡在 {@code allocated} 上——
 * 帳面看得到、實際賣不掉。
 *
 * <p><b>釋放時機必須晚於 {@code stockKeyTtlBuffer}。</b>
 * 活動剛結束時，可能還有補償訊息在佇列裡排隊要退庫；
 * 此時就把 Redis 的剩餘量結算掉，那些稍後才退回的量會被算漏——
 * 對系統而言就是憑空少了一批貨。
 *
 * <p>這也是為什麼緩衝期在預熱時就寫進了 Redis 鍵的 TTL：
 * 兩邊用的是同一個設定值，不會各自漂移。
 */
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

    /**
     * 確認活動已結束且過了緩衝期。
     *
     * <p><b>排程會過濾，但手動觸發不會——所以檢查必須放在這裡。</b>
     * 對一場還在進行的活動執行釋放有兩個後果：
     * Redis 鍵被丟棄，正在搶購的人全部拿到「尚未預熱」；
     * 而那一刻的剩餘量會被當成最終未售量結算，
     * 之後才被消費的補償訊息退回的量就再也沒有地方可去。
     *
     * <p>要提早結束一場活動，正確做法是先把活動下架並讓它的結束時間過去，
     * 而不是繞過緩衝期直接結算。
     */
    private void requireCooledDown(SeckillActivity activity) {
        Instant cooledDownAt = activity.period().endAt().plus(policy.stockKeyTtlBuffer());
        if (clock.instant().isBefore(cooledDownAt)) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_COOLED_DOWN,
                    "活動 %d 要到 %s 之後才可釋放庫存".formatted(activity.id(), cooledDownAt));
        }
    }

    /**
     * 讀 Redis 剩餘量 → 更新 MySQL → 丟棄 Redis 鍵。
     *
     * <p>順序與劃撥相反，理由一樣是「往少賣的方向倒」：
     * 若在丟棄 Redis 鍵之後才更新 MySQL 而中途失敗，那批未售量兩邊都不存在，
     * 就真的消失了。先落 MySQL，最壞情況只是 Redis 鍵多留到 TTL 到期。
     */
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
