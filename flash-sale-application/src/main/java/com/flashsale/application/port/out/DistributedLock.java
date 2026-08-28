package com.flashsale.application.port.out;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 分散式鎖埠（出站）。
 *
 * <p><b>刻意設計成模板方法而非 lock()/unlock() 成對 API</b>：
 * 呼叫端不可能忘記解鎖，也不可能在 finally 之外釋放別人的鎖。
 *
 * <p><b>本專案沒有用它包住庫存扣減</b>。Lua 腳本已在 Redis 單執行緒模型下保證原子性，
 * 再加一層分散式鎖只會把並行請求串行化，白白損失絕大部分吞吐。
 * 它被用在真正需要互斥的地方：快取重建（防擊穿）與補償排程的節點互斥。詳見 ADR-0003。
 */
public interface DistributedLock {

    /**
     * 取得鎖後執行，結束自動釋放。
     *
     * @param waitTime  取鎖等待上限，逾時拋 {@code BusinessException(LOCK_ACQUIRE_FAILED)}
     * @param leaseTime 持鎖上限，避免持有者當機造成死鎖
     */
    <T> T executeWithLock(String lockKey, Duration waitTime, Duration leaseTime, Supplier<T> action);

    /**
     * 嘗試取鎖，取不到就直接跳過（不等待、不拋例外）。
     *
     * <p>適用於「多節點排程只需一個節點執行」的場景。
     *
     * @return {@code true} 表示本次確實取得鎖並執行了動作
     */
    boolean tryExecuteWithLock(String lockKey, Duration leaseTime, Runnable action);
}
