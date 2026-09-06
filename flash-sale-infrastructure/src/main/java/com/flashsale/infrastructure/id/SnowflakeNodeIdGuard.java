package com.flashsale.infrastructure.id;

import com.flashsale.infrastructure.config.FlashSaleProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/** 確保沒有兩個活著的節點用同一個 {@code snowflake.node-id}。 */
@Component
public class SnowflakeNodeIdGuard {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeNodeIdGuard.class);

    private static final String KEY_PREFIX = "seckill:node-id:";

    /** 太長則 kill -9 後要等很久才能重啟，太短則一次 GC 停頓就會誤判租約失效。 */
    private static final Duration LEASE = Duration.ofSeconds(30);

    /** 取租期的三分之一：連續失敗兩次都還有機會補救。 */
    private static final long RENEW_INTERVAL_MILLIS = 10_000L;

    /** 續租與釋放前先比對：少了它，租約過期的節點會把接手者的租約蓋掉。 */
    private final String instanceToken = UUID.randomUUID().toString();

    private final StringRedisTemplate redisTemplate;
    private final String key;
    private final long nodeId;

    /** Redis 連不上時為 false，此時續租與釋放都不該再動任何東西。 */
    private volatile boolean holdsClaim;

    public SnowflakeNodeIdGuard(StringRedisTemplate redisTemplate, FlashSaleProperties properties) {
        this.redisTemplate = redisTemplate;
        this.nodeId = properties.snowflake().nodeId();
        this.key = KEY_PREFIX + nodeId;
        this.holdsClaim = claim();
    }

    private boolean claim() {
        Boolean acquired;
        try {
            acquired = redisTemplate.opsForValue().setIfAbsent(key, instanceToken, LEASE);
        } catch (RuntimeException redisUnavailable) {
            // 放行：Redis 掛了這個節點本來就做不了秒殺，擋著只是把依賴故障放大成部署失敗
            log.warn("無法向 Redis 宣告節點編號 {}，略過檢查。"
                    + "多副本部署時請自行確認 snowflake.node-id 不重複", nodeId, redisUnavailable);
            return false;
        }

        if (Boolean.TRUE.equals(acquired)) {
            log.info("節點編號 {} 宣告成功，租期 {} 秒", nodeId, LEASE.toSeconds());
            return true;
        }

        throw new IllegalStateException(
                ("snowflake.node-id=%d 已被另一個存活中的節點佔用。"
                        + "兩個節點共用同一個編號會發出重複的訂單號，因此拒絕啟動。%n"
                        + "  多副本部署：請給每個節點不同的 --flash-sale.snowflake.node-id（0-1023）%n"
                        + "  剛剛強制中止過同一個節點：租約最多 %d 秒後自動失效，稍候再試")
                        .formatted(nodeId, LEASE.toSeconds()));
    }

    /** 唯一刻意不做跨節點互斥的排程：每個節點續的是自己的租約，加鎖反而會讓它過期。 */
    @Scheduled(fixedDelay = RENEW_INTERVAL_MILLIS, initialDelay = RENEW_INTERVAL_MILLIS)
    public void renew() {
        if (!holdsClaim) {
            return;
        }
        try {
            if (!instanceToken.equals(redisTemplate.opsForValue().get(key))) {
                // 租約被接手了，這個節點可能正在發出重複識別碼，而它自己修不了
                log.error("節點編號 {} 的租約已被其他節點接手，"
                        + "本節點可能正在發出重複的識別碼，請儘快重啟並指定未被使用的編號", nodeId);
                holdsClaim = false;
                return;
            }
            redisTemplate.expire(key, LEASE);
        } catch (RuntimeException redisUnavailable) {
            // 不改 holdsClaim：一次抖動不代表租約沒了
            log.warn("節點編號 {} 續租失敗，將於下一輪重試", nodeId, redisUnavailable);
        }
    }

    /** 正常關機時交還編號。被 kill -9 時不會執行，那正是租期存在的理由。 */
    @PreDestroy
    public void release() {
        if (!holdsClaim) {
            return;
        }
        try {
            // 先比對再刪，否則刪掉的可能是別人的
            if (instanceToken.equals(redisTemplate.opsForValue().get(key))) {
                redisTemplate.delete(key);
                log.info("節點編號 {} 已交還", nodeId);
            }
        } catch (RuntimeException ignored) {
            log.debug("節點編號 {} 交還失敗，將由租期自動失效", nodeId);
        }
    }
}
