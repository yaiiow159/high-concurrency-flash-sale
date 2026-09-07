package com.flashsale.infrastructure.adapter.out.redis;

import com.flashsale.application.port.out.ChallengeReplayGuard;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/** SET NX + TTL：一次往返、原子。存的是 token 的雜湊，不是 token 本身。 */
@Component
public class RedisChallengeReplayGuard implements ChallengeReplayGuard {

    private static final String PREFIX = "risk:challenge:";

    private final StringRedisTemplate redis;

    public RedisChallengeReplayGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean firstUse(String challengeToken, Duration ttl) {
        Boolean set = redis.opsForValue().setIfAbsent(PREFIX + digest(challengeToken), "1", ttl);
        return Boolean.TRUE.equals(set);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
