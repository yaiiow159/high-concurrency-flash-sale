package com.flashsale.infrastructure.adapter.out.redis;

import com.flashsale.application.port.out.RiskSignalStore;
import com.flashsale.domain.risk.RiskSignals;
import com.flashsale.infrastructure.config.RiskProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 風險訊號放 Redis 而不是 MySQL：它們只描述最近幾分鐘，自動過期比排程清理便宜。
 * 這是冷路徑（領資格），幾次往返可以接受；熱路徑一次都不碰這裡。
 *
 * key 帶固定時間桶（epoch / window）：桶到點就換新的，計數才會真的歸零。
 * 先前每次觀察都把同一個 key 的 TTL 推回去，只要流量不斷窗口就永遠不關，
 * 「同 IP 帳號數」變成累計值單調成長——被拒的人重試一次就多一分，永遠回不去。
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
        long bucket = observation.now().getEpochSecond() / Math.max(window.getSeconds(), 1);
        long accountAge = Math.max(0, observation.now().getEpochSecond() - observation.accountCreatedAt().getEpochSecond());
        int usersOnIp = countDistinctUsers("ip:" + normalize(observation.clientIp()), bucket, observation.userId());
        int usersOnDevice = countDistinctUsers("device:" + normalize(observation.deviceId()), bucket, observation.userId());
        int qualifications = countQualifications(observation.userId(), observation.activityId(), bucket);
        return new RiskSignals(accountAge, usersOnIp, usersOnDevice, qualifications);
    }

    /** 沒有這個維度的觀察（IP 拿不到、沒帶裝置 ID）不參與計數，回 0 而不是把大家都算進同一桶。 */
    private int countDistinctUsers(String dimension, long bucket, Long userId) {
        if (dimension.endsWith(":")) {
            return 0;
        }
        String key = PREFIX + dimension + ":" + bucket;
        redis.opsForSet().add(key, String.valueOf(userId));
        // 桶到點後不會再有新寫入，TTL 只是讓它在窗口結束後被清掉；重設不會延長窗口
        redis.expire(key, window);
        Long size = redis.opsForSet().size(key);
        return size == null ? 0 : size.intValue();
    }

    /** 帶 activityId：同時關注三檔活動的正常使用者不該被算成「反覆領取」。 */
    private int countQualifications(Long userId, Long activityId, long bucket) {
        String key = PREFIX + "user:" + userId + ":activity:" + activityId + ":" + bucket;
        Long count = redis.opsForValue().increment(key);
        redis.expire(key, window);
        return count == null ? 0 : count.intValue();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
