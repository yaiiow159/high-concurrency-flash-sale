package com.flashsale.infrastructure.adapter.out.ratelimit;

/** 分散式限流器。 */
public interface UserRateLimiter {

    /**
     * 嘗試取得令牌。
     *
     * @param scope 限流維度（如 {@code seckill}），與 key 組成桶的識別
     * @return 放行為 {@code true}
     */
    boolean tryAcquire(String scope, String key);
}
