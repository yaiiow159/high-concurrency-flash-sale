package com.flashsale.infrastructure.adapter.out.redis;

import com.flashsale.application.port.out.RiskSignalStore;
import com.flashsale.domain.risk.RiskSignals;
import com.flashsale.infrastructure.config.RiskProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 風險訊號放 Redis 而不是 MySQL：它們只描述最近幾分鐘，自動過期比排程清理便宜。
 * 這是冷路徑（領資格），三次往返可以接受；熱路徑一次都不碰這裡。
 */
@Component
public class RedisRiskSignalStore implements RiskSignalStore {

    private static final String PREFIX = "risk:";

    private final StringRedisTemplate redis;
    private final Duration window;

    public RedisRiskSignalStore(StringRedisTemplate redis, RiskProperties properties) {
        this.redis = redis;
        this.window = properties.signalWindow();
    }

    @Override
    public RiskSignals observe(Observation observation) {
        long accountAge = Math.max(0, observation.now().getEpochSecond() - observation.accountCreatedAt().getEpochSecond());
        int usersOnIp = countDistinctUsers("ip:" + normalize(observation.clientIp()), observation.userId());
        int usersOnDevice = countDistinctUsers("device:" + normalize(observation.deviceId()), observation.userId());
        int qualifications = countQualifications(observation.userId());
        return new RiskSignals(accountAge, usersOnIp, usersOnDevice, qualifications);
    }

    /** 沒有這個維度的觀察（IP 拿不到、沒帶裝置 ID）不參與計數，回 0 而不是把大家都算進同一桶。 */
    private int countDistinctUsers(String key, Long userId) {
        if (key.endsWith(":")) {
            return 0;
        }
        String fullKey = PREFIX + key;
        redis.opsForSet().add(fullKey, String.valueOf(userId));
        redis.expire(fullKey, window);
        Long size = redis.opsForSet().size(fullKey);
        return size == null ? 0 : size.intValue();
    }

    private int countQualifications(Long userId) {
        String key = PREFIX + "user:" + userId + ":qualifications";
        Long count = redis.opsForValue().increment(key);
        redis.expire(key, window);
        return count == null ? 0 : count.intValue();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
