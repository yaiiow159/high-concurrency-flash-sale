package com.flashsale.infrastructure.adapter.out.redis;

import com.flashsale.application.port.out.StockRepository;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.domain.stock.StockBinding;
import com.flashsale.domain.stock.StockDeductionOutcome;
import com.flashsale.domain.stock.StockDeductionResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 庫存埠的 Redis 實作——防超賣的核心。
 *
 * <p>扣減與退回<b>只能</b>透過 Lua；Redis 故障時 fail-closed，放行等於無上限超賣。
 * 腳本的回傳碼與 {@code StockDeductionOutcome} 是一組契約，改一邊要同步改另一邊。
 */
@Repository
public class RedisStockRepository implements StockRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisStockRepository.class);

    /** 活動未預熱時 {@link #availableStock} 的回傳值，與「餘量為 0」明確區分。 */
    private static final long STOCK_NOT_INITIALIZED = -1L;

    private static final Duration FALLBACK_AUXILIARY_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> deductScript;
    private final RedisScript<Long> restoreScript;
    /** activityId → 附屬鍵 TTL 秒數的本機快取。 */
    private final Cache<Long, Long> auxiliaryTtlCache = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public RedisStockRepository(StringRedisTemplate redisTemplate,
                                RedisScript<List> seckillDeductScript,
                                RedisScript<Long> seckillRestoreScript) {
        this.redisTemplate = redisTemplate;
        this.deductScript = seckillDeductScript;
        this.restoreScript = seckillRestoreScript;
    }

    @Override
    public StockDeductionResult deduct(Long activityId, Long userId, int quantity,
                                       int perUserLimit, String requestId, String orderNo) {
        List<String> keys = activityKeys(activityId);
        long ttlSeconds = resolveAuxiliaryKeyTtlSeconds(activityId);

        List<?> raw = executeDeduct(keys, activityId, userId, quantity, perUserLimit,
                requestId, orderNo, ttlSeconds);

        StockDeductionOutcome outcome = StockDeductionOutcome.fromCode(((Number) raw.get(0)).longValue());
        // Lua 回傳的是完整憑證（orderNo|userId|quantity），對外只暴露訂單號。
        String boundOrderNo = StockBindingCodec.extractOrderNo(asNullableString(raw.get(1)));

        return switch (outcome) {
            case SUCCESS -> StockDeductionResult.success(boundOrderNo);
            case DUPLICATE_REQUEST -> StockDeductionResult.duplicate(boundOrderNo);
            default -> StockDeductionResult.rejected(outcome);
        };
    }

    @Override
    public boolean restore(Long activityId, Long userId, int quantity, String requestId) {
        try {
            Long result = redisTemplate.execute(restoreScript, activityKeys(activityId),
                    String.valueOf(userId), String.valueOf(quantity), requestId);
            return result != null && result == 1L;
        } catch (DataAccessException e) {
            // 退庫失敗必須讓呼叫端知道，才能重試或轉人工。靜默失敗等於永久少賣。
            throw new BusinessException(ErrorCode.STOCK_SERVICE_UNAVAILABLE,
                    "庫存退回失敗 activityId=" + activityId + ", requestId=" + requestId, e);
        }
    }

    @Override
    public void initialize(Long activityId, int totalStock, Duration ttl, boolean force) {
        auxiliaryTtlCache.invalidate(activityId);
        String key = RedisKeys.stock(activityId);
        String value = String.valueOf(totalStock);
        try {
            if (force) {
                redisTemplate.opsForValue().set(key, value, ttl);
                log.warn("強制覆寫活動 {} 庫存為 {}（僅限維運補救）", activityId, totalStock);
            } else {
                // SET NX：鍵已存在時不動它，避免把已賣出的量加回去。
                Boolean created = redisTemplate.opsForValue().setIfAbsent(key, value, ttl);
                log.info("活動 {} 庫存初始化：{}", activityId,
                        Boolean.TRUE.equals(created) ? "寫入 " + totalStock : "已存在，略過");
            }
        } catch (DataAccessException e) {
            throw new BusinessException(ErrorCode.STOCK_SERVICE_UNAVAILABLE,
                    "庫存預熱失敗 activityId=" + activityId, e);
        }
    }

    @Override
    public long availableStock(Long activityId) {
        try {
            String value = redisTemplate.opsForValue().get(RedisKeys.stock(activityId));
            return value == null ? STOCK_NOT_INITIALIZED : Long.parseLong(value);
        } catch (DataAccessException | NumberFormatException e) {
            log.warn("讀取活動 {} 庫存餘量失敗，以未初始化處理", activityId, e);
            return STOCK_NOT_INITIALIZED;
        }
    }

    @Override
    public void discard(Long activityId) {
        auxiliaryTtlCache.invalidate(activityId);
        try {
            redisTemplate.delete(activityKeys(activityId));
            log.info("已丟棄活動 {} 的庫存鍵", activityId);
        } catch (DataAccessException e) {
            // 丟棄失敗不影響正確性——鍵本來就有 TTL，最多是晚一點才回收記憶體。
            // 這是少數可以吞掉的 Redis 錯誤，因為它不是任何判斷的依據。
            log.warn("丟棄活動 {} 的庫存鍵失敗，將由 TTL 自行回收", activityId, e);
        }
    }

    private List<?> executeDeduct(List<String> keys, Long activityId, Long userId, int quantity,
                                  int perUserLimit, String requestId, String orderNo, long ttlSeconds) {
        try {
            List<?> raw = redisTemplate.execute(deductScript, keys,
                    String.valueOf(userId),
                    String.valueOf(quantity),
                    String.valueOf(perUserLimit),
                    requestId,
                    orderNo,
                    String.valueOf(ttlSeconds));
            if (raw == null || raw.size() < 2) {
                throw new IllegalStateException("Lua 腳本回傳格式不符預期: " + raw);
            }
            return raw;
        } catch (DataAccessException e) {
            // Redis 掛掉時「絕不」降級放行——放行等於無上限超賣。寧可整個活動不可用。
            throw new BusinessException(ErrorCode.STOCK_SERVICE_UNAVAILABLE,
                    "庫存扣減失敗 activityId=" + activityId, e);
        }
    }

    private List<String> activityKeys(Long activityId) {
        return List.of(
                RedisKeys.stock(activityId),
                RedisKeys.userPurchased(activityId),
                RedisKeys.requestBinding(activityId));
    }

    /** 附屬鍵（限購、冪等）的 TTL 對齊庫存鍵，結果快取在本機。 */
    private long resolveAuxiliaryKeyTtlSeconds(Long activityId) {
        return auxiliaryTtlCache.get(activityId, this::queryStockKeyTtlSeconds);
    }

    private long queryStockKeyTtlSeconds(Long activityId) {
        Long ttl = redisTemplate.getExpire(RedisKeys.stock(activityId));
        return (ttl == null || ttl <= 0) ? FALLBACK_AUXILIARY_TTL.toSeconds() : ttl;
    }

    /** {@inheritDoc} */
    @Override
    public void scanBindings(Long activityId, int batchSize, Consumer<List<StockBinding>> batchConsumer) {
        String key = RedisKeys.requestBinding(activityId);
        ScanOptions options = ScanOptions.scanOptions().count(batchSize).build();

        List<StockBinding> batch = new ArrayList<>(batchSize);
        try (Cursor<Map.Entry<Object, Object>> cursor = redisTemplate.opsForHash().scan(key, options)) {
            while (cursor.hasNext()) {
                Map.Entry<Object, Object> entry = cursor.next();
                batch.add(StockBindingCodec.decode(
                        String.valueOf(entry.getKey()), String.valueOf(entry.getValue())));

                if (batch.size() >= batchSize) {
                    batchConsumer.accept(List.copyOf(batch));
                    batch.clear();
                }
            }
        } catch (DataAccessException e) {
            throw new BusinessException(ErrorCode.STOCK_SERVICE_UNAVAILABLE,
                    "掃描扣減憑證失敗 activityId=" + activityId, e);
        }
        if (!batch.isEmpty()) {
            batchConsumer.accept(List.copyOf(batch));
        }
    }

    private static String asNullableString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }
}
