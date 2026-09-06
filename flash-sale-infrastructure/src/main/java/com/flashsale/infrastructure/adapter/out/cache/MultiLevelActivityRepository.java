package com.flashsale.infrastructure.adapter.out.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.DistributedLock;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.infrastructure.adapter.out.redis.RedisKeys;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** 多級快取活動查詢——以 <b>Decorator 模式</b>疊在資料庫實作之上。 */
@Repository
@Primary
public class MultiLevelActivityRepository implements ActivityRepository {

    private static final Logger log = LoggerFactory.getLogger(MultiLevelActivityRepository.class);

    /** 查無此活動時寫入的哨兵值，用來擋快取穿透。 */
    private static final String NULL_SENTINEL = "__NULL__";

    private static final Duration L1_TTL = Duration.ofSeconds(5);
    private static final Duration L2_BASE_TTL = Duration.ofMinutes(5);
    private static final Duration L2_TTL_JITTER = Duration.ofMinutes(1);
    private static final Duration NULL_TTL = Duration.ofSeconds(30);

    private static final Duration LOCK_WAIT = Duration.ofMillis(500);
    private static final Duration LOCK_LEASE = Duration.ofSeconds(3);

    private final ActivityRepository delegate;
    private final StringRedisTemplate redisTemplate;
    private final DistributedLock distributedLock;
    private final ObjectMapper objectMapper;

    /** L1 本機快取。 */
    private final Cache<Long, Optional<SeckillActivity>> localCache = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(L1_TTL)
            .build();

    private final Cache<String, List<SeckillActivity>> onlineListCache = Caffeine.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(L1_TTL)
            .build();

    public MultiLevelActivityRepository(@Qualifier("jpaActivityRepository") ActivityRepository delegate,
                                        StringRedisTemplate redisTemplate,
                                        DistributedLock distributedLock,
                                        ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.redisTemplate = redisTemplate;
        this.distributedLock = distributedLock;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<SeckillActivity> findById(Long activityId) {
        Optional<SeckillActivity> l1 = localCache.getIfPresent(activityId);
        if (l1 != null) {
            return l1;
        }

        Optional<SeckillActivity> l2 = readFromRedis(activityId);
        if (l2 != null) {
            localCache.put(activityId, l2);
            return l2;
        }

        return rebuild(activityId);
    }

    /** 後台清單<b>一律直接回源，不經過任何一層快取</b>。 */
    @Override
    public List<SeckillActivity> findAllForAdmin(int limit, int offset) {
        return delegate.findAllForAdmin(limit, offset);
    }

    @Override
    public List<SeckillActivity> findOnlineActivities() {
        // 列表查詢不在秒殺熱路徑上（只有首頁會呼叫），單層本機快取已足夠。
        // 不為了對稱而硬套三級結構——沒有必要的複雜度就是負債。
        return onlineListCache.get(RedisKeys.onlineActivitiesCache(), key -> delegate.findOnlineActivities());
    }

    /** 寫入後立刻讓快取失效。 */
    @Override
    public SeckillActivity update(SeckillActivity activity) {
        SeckillActivity updated = delegate.update(activity);
        evict(activity.id());
        return updated;
    }

    private void evict(Long activityId) {
        try {
            redisTemplate.delete(RedisKeys.activityCache(activityId));
        } catch (RuntimeException e) {
            // Redis 掛了不該讓下架失敗——資料庫已經寫進去了，L2 最多 6 分鐘後也會自己過期。
            // 但要記下來，讓人知道這段期間快取是髒的
            log.warn("活動 {} 的 L2 快取清除失敗，最壞需等 TTL 過期才會一致", activityId, e);
        }
        localCache.invalidate(activityId);
        // 上架清單也含這個活動，不清的話首頁會繼續列出已下架的活動
        onlineListCache.invalidate(RedisKeys.onlineActivitiesCache());
    }

    /** 對帳查詢<b>刻意不走快取</b>，直接回源。 */
    @Override
    public List<SeckillActivity> findForReconciliation(Instant endedAfter) {
        return delegate.findForReconciliation(endedAfter);
    }

    /** 同樣繞過快取：釋放會實際改動庫存數字，依據不可以是舊資料。 */
    @Override
    public List<SeckillActivity> findEndedBefore(Instant endedBefore) {
        return delegate.findEndedBefore(endedBefore);
    }

    /** 回源重建快取，以分散式鎖防擊穿。 */
    private Optional<SeckillActivity> rebuild(Long activityId) {
        try {
            return distributedLock.executeWithLock(
                    RedisKeys.cacheRebuildLock(activityId), LOCK_WAIT, LOCK_LEASE,
                    () -> loadAndPopulate(activityId));
        } catch (BusinessException e) {
            if (e.errorCode() == ErrorCode.LOCK_ACQUIRE_FAILED) {
                log.debug("活動 {} 快取重建鎖競爭失敗，本次直接回源", activityId);
                return delegate.findById(activityId);
            }
            throw e;
        }
    }

    private Optional<SeckillActivity> loadAndPopulate(Long activityId) {
        // Double-check：等鎖期間可能已有其他執行緒把快取填好了。
        Optional<SeckillActivity> refreshed = readFromRedis(activityId);
        if (refreshed != null) {
            localCache.put(activityId, refreshed);
            return refreshed;
        }

        Optional<SeckillActivity> fromDb = delegate.findById(activityId);
        writeToRedis(activityId, fromDb);
        localCache.put(activityId, fromDb);
        return fromDb;
    }

    /** 讀取 L2。 */
    private Optional<SeckillActivity> readFromRedis(Long activityId) {
        String cached;
        try {
            cached = redisTemplate.opsForValue().get(RedisKeys.activityCache(activityId));
        } catch (DataAccessException e) {
            // 快取故障不該讓查詢失敗——降級直接回源，這是快取的本分。
            log.warn("讀取活動 {} 的 L2 快取失敗，降級回源", activityId, e);
            return null;
        }

        if (cached == null) {
            return null;
        }
        if (NULL_SENTINEL.equals(cached)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(cached, ActivityCachePayload.class).toDomain());
        } catch (JsonProcessingException | RuntimeException e) {
            // 快取內容格式不符（多半是版本升級後的舊資料），視為未命中並清掉它。
            log.warn("活動 {} 的快取內容無法反序列化，清除後回源", activityId, e);
            redisTemplate.delete(RedisKeys.activityCache(activityId));
            return null;
        }
    }

    private void writeToRedis(Long activityId, Optional<SeckillActivity> activity) {
        String key = RedisKeys.activityCache(activityId);
        try {
            if (activity.isEmpty()) {
                // 空值哨兵用短 TTL：活動剛建立時不該被「不存在」的快取擋住太久。
                redisTemplate.opsForValue().set(key, NULL_SENTINEL, NULL_TTL);
                return;
            }
            String json = objectMapper.writeValueAsString(ActivityCachePayload.from(activity.get()));
            redisTemplate.opsForValue().set(key, json, jitteredTtl());
        } catch (JsonProcessingException | DataAccessException e) {
            log.warn("寫入活動 {} 的 L2 快取失敗，本次僅使用本機快取", activityId, e);
        }
    }

    /** TTL 加上隨機抖動，避免同批預熱的活動在同一秒集體過期造成雪崩。 */
    private Duration jitteredTtl() {
        long jitterMillis = ThreadLocalRandom.current().nextLong(L2_TTL_JITTER.toMillis());
        return L2_BASE_TTL.plusMillis(jitterMillis);
    }
}
