package com.flashsale.infrastructure.adapter.out.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * 令牌桶限流的 Redis 實作。
 *
 * <p><b>故障時放行（fail-open）</b>：Redis 不可用時直接放行，而非拒絕所有請求。
 * 這與庫存扣減的 fail-closed 策略相反，兩者的取捨依據是「失效的後果」：
 * <ul>
 *   <li>限流失效 → 少擋了一些刷單流量，後面還有庫存扣減這道真正的關卡</li>
 *   <li>庫存失效 → 直接超賣，是不可逆的業務損失</li>
 * </ul>
 * 降級策略不該一刀切，要看這道防線失守會付出什麼代價。
 */
@Component
public class RedisTokenBucketRateLimiter implements UserRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);
    private static final String KEY_PREFIX = "seckill:rl:";
    private static final String TOKENS_PER_REQUEST = "1";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> rateLimitScript;
    private final Clock clock;
    private final int capacity;
    private final double refillRatePerSecond;

    public RedisTokenBucketRateLimiter(
            StringRedisTemplate redisTemplate,
            RedisScript<List> rateLimitScript,
            Clock clock,
            @Value("${flash-sale.rate-limit.capacity:5}") int capacity,
            @Value("${flash-sale.rate-limit.refill-per-second:1}") double refillRatePerSecond) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = rateLimitScript;
        this.clock = clock;
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
    }

    @Override
    public boolean tryAcquire(String scope, String key) {
        try {
            List<?> result = redisTemplate.execute(rateLimitScript,
                    List.of(KEY_PREFIX + scope + ":" + key),
                    String.valueOf(capacity),
                    String.valueOf(refillRatePerSecond),
                    TOKENS_PER_REQUEST,
                    String.valueOf(clock.millis()));
            return result != null && ((Number) result.get(0)).intValue() == 1;
        } catch (DataAccessException e) {
            log.warn("限流檢查失敗，本次放行（fail-open）scope={}, key={}", scope, key, e);
            return true;
        }
    }
}
