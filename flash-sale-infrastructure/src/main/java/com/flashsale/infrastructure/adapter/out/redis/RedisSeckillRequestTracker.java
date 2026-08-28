package com.flashsale.infrastructure.adapter.out.redis;

import com.flashsale.application.port.out.SeckillRequestTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 搶購受理狀態的 Redis 實作。
 *
 * <p>以 Hash 儲存，欄位刻意保持精簡——這些鍵在秒殺尖峰會瞬間產生數十萬個，
 * 每個欄位的位元組成本都會乘上訂單量。
 *
 * <p>TTL 設得比付款期限略長：狀態只需存活到「訂單確定落庫或確定失敗」為止，
 * 之後查詢一律走資料庫。這不是永久儲存，只是填補非同步空窗的臨時看板。
 */
@Component
public class RedisSeckillRequestTracker implements SeckillRequestTracker {

    private static final Logger log = LoggerFactory.getLogger(RedisSeckillRequestTracker.class);

    private static final String FIELD_USER_ID = "u";
    private static final String FIELD_FAILED = "f";
    private static final String FIELD_REASON = "r";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;

    public RedisSeckillRequestTracker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void markAccepted(String orderNo, Long userId) {
        String key = RedisKeys.requestStatus(orderNo);
        redisTemplate.opsForHash().putAll(key, Map.of(
                FIELD_USER_ID, String.valueOf(userId),
                FIELD_FAILED, "0"));
        redisTemplate.expire(key, TTL);
    }

    @Override
    public void markFailed(String orderNo, String reason) {
        String key = RedisKeys.requestStatus(orderNo);
        try {
            redisTemplate.opsForHash().putAll(key, Map.of(
                    FIELD_FAILED, "1",
                    FIELD_REASON, reason));
            redisTemplate.expire(key, TTL);
        } catch (DataAccessException e) {
            // 標記失敗只影響前端的輪詢體驗，不影響資料正確性——不值得讓補償流程因此中斷。
            log.warn("標記訂單 {} 受理失敗時發生錯誤，前端將輪詢至逾時", orderNo, e);
        }
    }

    @Override
    public Optional<RequestStatus> find(String orderNo) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(RedisKeys.requestStatus(orderNo));
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        if ("1".equals(asString(entries.get(FIELD_FAILED)))) {
            return Optional.of(RequestStatus.failed(orderNo, asString(entries.get(FIELD_REASON))));
        }
        return Optional.of(RequestStatus.accepted(orderNo, parseUserId(entries.get(FIELD_USER_ID))));
    }

    private static Long parseUserId(Object raw) {
        String text = asString(raw);
        return text == null ? null : Long.valueOf(text);
    }

    private static String asString(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}
