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

/**
 * 確保沒有兩個活著的節點用同一個 {@code snowflake.node-id}。
 *
 * <h2>為什麼需要這個</h2>
 *
 * <p>{@link SnowflakeIdGenerator} 已經擋掉了超出範圍的設定值，但它擋不掉
 * <b>兩個節點都設成同一個號碼</b>——而預設值是 0，
 * 也就是說<b>多開一個副本卻忘記改設定，是零阻力就會發生的事</b>。
 *
 * <p>後果是同一毫秒內兩個節點發出完全相同的識別碼。症狀不是「錯誤訊息」，
 * 而是訂單唯一索引在尖峰時段開始爆——單節點測試永遠測不到，
 * 而它偏偏只在流量夠大、兩個節點剛好撞在同一毫秒時才出現。
 *
 * <h2>為什麼是 fail-closed（起不來，而不是警告後照跑）</h2>
 *
 * <p>兩種失敗的代價差很多：
 *
 * <ul>
 *   <li><b>誤判而拒絕啟動</b>：吵、立刻被發現、改一個設定就好</li>
 *   <li><b>漏判而發出重複識別碼</b>：安靜，直到尖峰時炸開，
 *       而那時已經有一批訂單帶著錯誤的號碼落庫了</li>
 * </ul>
 *
 * <p>這與 CLAUDE.md 對庫存的判準一致：看「這道防線失守會付出什麼代價」，
 * 而不是一律 fail-open 或一律 fail-closed。
 *
 * <p><b>但 Redis 本身連不上時放行。</b> 此時這個節點本來就做不了秒殺
 * （庫存在 Redis），擋著不讓它啟動並不會多防到什麼，
 * 只是把一個依賴故障放大成部署失敗。
 *
 * <h2>租約而不是永久佔用</h2>
 *
 * <p>宣告寫進 Redis 時帶 TTL，並由本節點定期續租。
 * 用永久鍵的話，節點當掉之後那個號碼就再也沒有人能用，
 * 而「重啟一個當掉的服務」是維運最常做的動作。
 */
@Component
public class SnowflakeNodeIdGuard {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeNodeIdGuard.class);

    private static final String KEY_PREFIX = "seckill:node-id:";

    /**
     * 租期。
     *
     * <p>抓 30 秒是在兩個方向之間取捨：太長的話，節點被 kill -9
     * （沒跑到 {@link #release()}）之後要等很久才能用同一個號碼重啟——
     * 而那正是開發時每次重啟都會遇到的情況。
     * 太短則會讓一次 GC 停頓或網路抖動就讓租約過期，
     * 於是另一個節點誤以為這個號碼沒人用。
     */
    private static final Duration LEASE = Duration.ofSeconds(30);

    /** 續租頻率取租期的三分之一：連續失敗兩次都還有機會補救。 */
    private static final long RENEW_INTERVAL_MILLIS = 10_000L;

    /**
     * 本次啟動的識別。
     *
     * <p>續租與釋放都要先確認「這個號碼還是我的」——
     * 少了這個比對，一個租約已經過期、號碼被別人接手的節點，
     * 會在下一次續租時把別人的租約蓋掉。
     */
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
            // 見類別註解：Redis 不可用時放行
            log.warn("無法向 Redis 宣告節點編號 {}，略過檢查。"
                    + "多副本部署時請自行確認 snowflake.node-id 不重複", nodeId, redisUnavailable);
            return false;
        }

        if (Boolean.TRUE.equals(acquired)) {
            log.info("節點編號 {} 宣告成功，租期 {} 秒", nodeId, LEASE.toSeconds());
            return true;
        }

        // 已被佔用。**唯一的例外是它本來就是我們自己**——
        // 同一個 JVM 不會走到這裡，但重啟後拿到同一個 token 的機率是零，
        // 因此這裡一律視為衝突
        throw new IllegalStateException(
                ("snowflake.node-id=%d 已被另一個存活中的節點佔用。"
                        + "兩個節點共用同一個編號會發出重複的訂單號，因此拒絕啟動。%n"
                        + "  多副本部署：請給每個節點不同的 --flash-sale.snowflake.node-id（0-1023）%n"
                        + "  剛剛強制中止過同一個節點：租約最多 %d 秒後自動失效，稍候再試")
                        .formatted(nodeId, LEASE.toSeconds()));
    }

    /**
     * 續租。
     *
     * <p><b>這是唯一一個刻意不做跨節點互斥的排程</b>——其他排程加鎖是為了
     * 「同一件事只做一次」，而這裡每個節點續的是自己的租約，
     * 加鎖反而會讓沒搶到鎖的節點租約過期。
     */
    @Scheduled(fixedDelay = RENEW_INTERVAL_MILLIS, initialDelay = RENEW_INTERVAL_MILLIS)
    public void renew() {
        if (!holdsClaim) {
            return;
        }
        try {
            if (!instanceToken.equals(redisTemplate.opsForValue().get(key))) {
                // 租約已經過期並被別人接手。此時這個節點正在發出可能重複的識別碼，
                // 而它自己修不了——只能吵，讓人看見
                log.error("節點編號 {} 的租約已被其他節點接手，"
                        + "本節點可能正在發出重複的識別碼，請儘快重啟並指定未被使用的編號", nodeId);
                holdsClaim = false;
                return;
            }
            redisTemplate.expire(key, LEASE);
        } catch (RuntimeException redisUnavailable) {
            // 不改 holdsClaim：一次抖動不代表租約沒了，下一輪還會再試
            log.warn("節點編號 {} 續租失敗，將於下一輪重試", nodeId, redisUnavailable);
        }
    }

    /**
     * 正常關機時交還編號，讓同一個編號可以立刻重用。
     *
     * <p>被 kill -9 時這裡不會執行，那正是租期存在的理由。
     */
    @PreDestroy
    public void release() {
        if (!holdsClaim) {
            return;
        }
        try {
            // 先比對再刪：租約若已過期並被別人接手，刪掉的會是別人的
            if (instanceToken.equals(redisTemplate.opsForValue().get(key))) {
                redisTemplate.delete(key);
                log.info("節點編號 {} 已交還", nodeId);
            }
        } catch (RuntimeException ignored) {
            // 關機路徑上不值得為此拋例外，租約會自己過期
            log.debug("節點編號 {} 交還失敗，將由租期自動失效", nodeId);
        }
    }
}
