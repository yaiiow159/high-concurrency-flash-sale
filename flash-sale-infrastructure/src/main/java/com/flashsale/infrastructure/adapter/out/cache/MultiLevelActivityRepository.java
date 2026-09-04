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

/**
 * 多級快取活動查詢——以 <b>Decorator 模式</b>疊在資料庫實作之上。
 *
 * <p>應用層注入的是 {@code ActivityRepository} 介面，完全不知道有快取存在。
 * 要拆掉這層快取，只需移除 {@code @Primary}，一行 Use Case 都不用改。
 *
 * <p><b>三層結構與各自要解決的問題</b>：
 * <pre>
 *   L1  Caffeine（本機，秒級 TTL）  ── 消滅同節點內的重複查詢，零網路成本
 *   L2  Redis（叢集共享，分鐘級）   ── 消滅跨節點的重複查詢，保護資料庫
 *   L3  MySQL（delegate）           ── 唯一的真實來源
 * </pre>
 *
 * <p><b>快取三大災難的對策</b>：
 * <ul>
 *   <li><b>穿透</b>（查不存在的 id 反覆打 DB）：把「查無資料」也快取起來，用短 TTL 的空值哨兵</li>
 *   <li><b>擊穿</b>（熱點 key 過期瞬間萬人同時回源）：回源前先搶分散式鎖，只放一個執行緒進 DB</li>
 *   <li><b>雪崩</b>（大量 key 同時過期）：TTL 加上隨機抖動，把過期時間打散</li>
 * </ul>
 */
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

    /**
     * L1 本機快取。
     *
     * <p>TTL 只有 5 秒，但<b>那不代表活動下架後 5 秒就會生效</b>。
     * L1 過期後讀到的是 L2，而 L2 的 TTL 是 5~6 分鐘——
     * 兩層都沒有任何主動失效的路徑（全檔只有反序列化失敗時會刪鍵）。
     * 因此下架的實際最壞延遲是 <b>L2 的 6 分鐘</b>，不是 5 秒。
     *
     * <p>這句話先前寫成「最慢 5 秒內所有節點都會看到新狀態」，那是錯的。
     * 錯在它會讓維運在緊急下架一個有問題的活動後，
     * 以為沒生效是別的原因——而實際上請求還會照樣進來、庫存照樣扣好幾分鐘。
     *
     * <p>{@link #update} 會在寫入後主動清掉 L2 與本機 L1，因此
     * <b>經由管理端點下架時，這 5 秒是真的上界</b>：清完 L2 之後，
     * 其他節點最慢 5 秒（L1 TTL）就會回源看到新狀態。
     *
     * <p>但直接改資料庫仍然繞得過去——那條路沒有任何地方可以掛失效邏輯，
     * 最壞要等 L2 的 6 分鐘。營運要下架請走端點。
     */
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

    /**
     * 後台清單<b>一律直接回源，不經過任何一層快取</b>。
     *
     * <p>兩個理由。其一：後台看到的必須是當下的真實狀態——
     * 維運剛下架一檔活動，後台卻因為快取還顯示「上架中」，
     * 他會再按一次，而那才是真正危險的地方。
     *
     * <p>其二：這支查詢每天被呼叫幾十次，快取它省不到任何東西，
     * 卻多一個會過期、會失效、會出錯的東西。
     */
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

    /**
     * 寫入後立刻讓快取失效。
     *
     * <p><b>順序是先寫資料庫、再清快取。</b> 反過來（先清再寫）會開一個窗口：
     * 清完之後、commit 之前，有請求回源讀到<b>舊值</b>並把它重新寫進快取，
     * 於是下架完成的瞬間快取裡剛好又是「上架中」，而且這次沒有東西會再清它。
     *
     * <p><b>其他節點的 L1 仍會保留最多 5 秒。</b>Caffeine 是行程內的，
     * 刪 Redis 鍵刪不掉別台機器的記憶體。這正是 L1 TTL 只設 5 秒的理由——
     * 那 5 秒是「營運按下下架後，最壞多久所有節點都會看到」的上界。
     * 要真正即時就得再加一層 pub/sub 廣播，那是為了 5 秒引進一個新的故障點。
     */
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

    /**
     * 對帳查詢<b>刻意不走快取</b>，直接回源。
     *
     * <p>對帳的整個意義就是核對真實狀態。若讀到的是幾分鐘前的快取，
     * 核對出來的偏差是假的——可能報出根本不存在的問題，也可能漏掉真正的偏差。
     * 這類低頻的正確性檢查，不該為了省一次查詢而犧牲資料新鮮度。
     */
    @Override
    public List<SeckillActivity> findForReconciliation(Instant endedAfter) {
        return delegate.findForReconciliation(endedAfter);
    }

    /** 同樣繞過快取：釋放會實際改動庫存數字，依據不可以是舊資料。 */
    @Override
    public List<SeckillActivity> findEndedBefore(Instant endedBefore) {
        return delegate.findEndedBefore(endedBefore);
    }

    /**
     * 回源重建快取，以分散式鎖防擊穿。
     *
     * <p>取不到鎖時<b>直接讀資料庫</b>而非拋錯：等鎖逾時就報錯，會把一次快取失效放大成一次故障。
     * 少數請求穿透到 DB 的代價，遠低於對使用者回傳錯誤。
     */
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

    /**
     * 讀取 L2。
     *
     * <p>回傳值有三種語意，刻意用 {@code null} 與 {@code Optional.empty()} 區分：
     * <ul>
     *   <li>{@code null}：快取未命中，需要回源</li>
     *   <li>{@code Optional.empty()}：命中空值哨兵，確定此活動不存在，<b>不必</b>回源</li>
     *   <li>有值：正常命中</li>
     * </ul>
     */
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
