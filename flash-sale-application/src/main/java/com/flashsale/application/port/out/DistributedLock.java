package com.flashsale.application.port.out;

import java.time.Duration;
import java.util.function.Supplier;

/** 分散式鎖埠（出站）。 */
public interface DistributedLock {

    /**
     * 取得鎖後執行，結束自動釋放。
     *
     * @param waitTime  取鎖等待上限，逾時拋 {@code BusinessException(LOCK_ACQUIRE_FAILED)}
     * @param leaseTime 持鎖上限，避免持有者當機造成死鎖
     */
    <T> T executeWithLock(String lockKey, Duration waitTime, Duration leaseTime, Supplier<T> action);

    /** 嘗試取鎖，取不到就直接跳過（不等待、不拋例外）。 */
    boolean tryExecuteWithLock(String lockKey, Duration leaseTime, Runnable action);
}
