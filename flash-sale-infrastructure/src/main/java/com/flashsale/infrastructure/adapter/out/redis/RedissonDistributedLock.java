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
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 分散式鎖的 Redisson 實作。 */
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
            acquired = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(),
                    TimeUnit.MILLISECONDS);
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

    /** <b>{@code leaseTime} 在這個實作裡不會被使用。</b> */
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

    /** 釋放鎖。 */
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
