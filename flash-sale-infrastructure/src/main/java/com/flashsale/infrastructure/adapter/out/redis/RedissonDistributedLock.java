package com.flashsale.infrastructure.adapter.out.redis;

import com.flashsale.application.port.out.DistributedLock;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 分散式鎖的 Redisson 實作。
 *
 * <p>選 Redisson 而非自己刻 {@code SET NX PX} 的理由，是自刻版本必須自行處理三件難事：
 * <ul>
 *   <li><b>解鎖的原子性</b>——「比對持有者再刪除」必須是 Lua，否則會誤刪別人的鎖</li>
 *   <li><b>看門狗續期</b>——業務執行時間超過 lease 時自動續租，避免鎖提前失效</li>
 *   <li><b>可重入</b>——同執行緒巢狀取鎖不會自我死鎖</li>
 * </ul>
 * 這三件事每一件都足以在半夜引發事故，不值得為了少一個依賴而自己重寫。
 *
 * <p><b>使用範圍</b>：僅用於低頻的互斥場景（庫存預熱、快取重建、排程節點互斥）。
 * 秒殺熱路徑上沒有任何一處使用它——詳見 ADR-0003。
 */
@Component
public class RedissonDistributedLock implements DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(RedissonDistributedLock.class);

    private final RedissonClient redissonClient;

    public RedissonDistributedLock(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public <T> T executeWithLock(String lockKey, Duration waitTime, Duration leaseTime, Supplier<T> action) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new BusinessException(ErrorCode.LOCK_ACQUIRE_FAILED,
                        "取得鎖逾時: " + lockKey);
            }
            return action.get();
        } catch (InterruptedException e) {
            // 保留中斷旗標，否則上層執行緒池會失去正常關閉的能力。
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.LOCK_ACQUIRE_FAILED, "取鎖過程被中斷: " + lockKey, e);
        } finally {
            releaseQuietly(lock, lockKey, acquired);
        }
    }

    @Override
    public boolean tryExecuteWithLock(String lockKey, Duration leaseTime, Runnable action) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = lock.tryLock();
        if (!acquired) {
            log.debug("鎖 {} 已被其他節點持有，本次略過", lockKey);
            return false;
        }
        try {
            // tryLock() 無參數版本會啟用看門狗自動續期，長時間排程不會中途掉鎖。
            action.run();
            return true;
        } finally {
            releaseQuietly(lock, lockKey, true);
        }
    }

    /**
     * 釋放鎖。
     *
     * <p>{@code isHeldByCurrentThread} 的檢查不可省略：若業務執行時間超過 lease 導致鎖已自動過期
     * 並被他人取走，此時解鎖會拋 {@code IllegalMonitorStateException}，
     * 在 finally 中拋出會覆蓋掉真正的業務例外。
     */
    private void releaseQuietly(RLock lock, String lockKey, boolean acquired) {
        if (!acquired) {
            return;
        }
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            } else {
                log.warn("鎖 {} 已非本執行緒持有（可能因執行過久而過期），略過解鎖", lockKey);
            }
        } catch (RuntimeException e) {
            log.warn("釋放鎖 {} 失敗，將等待其自然過期", lockKey, e);
        }
    }
}
