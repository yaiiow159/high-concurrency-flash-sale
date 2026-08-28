package com.flashsale.infrastructure.adapter.out.redis;

import com.flashsale.domain.stock.StockDeductionOutcome;
import com.flashsale.domain.stock.StockDeductionResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 庫存扣減的整合測試——對著<b>真實的 Redis</b> 驗證。
 *
 * <p><b>為什麼一定要用真的 Redis 而不是 mock？</b>
 * 這裡要驗證的正是「Lua 腳本在 Redis 單執行緒模型下的原子性」。
 * mock 掉 Redis 就等於 mock 掉了唯一要驗證的東西，測試會全綠但超賣照樣發生。
 * 只有真的併發打上去，才能證明防超賣是成立的。
 */
@Testcontainers
@DisplayName("Redis 庫存扣減")
class RedisStockRepositoryTest {

    private static final Long ACTIVITY_ID = 1001L;
    private static final int PER_USER_LIMIT = 2;

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RedisStockRepository stockRepository;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        stockRepository = new RedisStockRepository(redisTemplate, deductScript(), restoreScript());
    }

    @Nested
    @DisplayName("防超賣")
    class OversellPrevention {

        @Test
        @DisplayName("1000 執行緒搶 100 件庫存：成功數必須剛好 100，餘量必須剛好 0")
        void neverOversellsUnderHeavyConcurrency() throws Exception {
            int stock = 100;
            int threads = 1000;
            stockRepository.initialize(ACTIVITY_ID, stock, Duration.ofMinutes(10), true);

            AtomicInteger succeeded = new AtomicInteger();
            AtomicInteger soldOut = new AtomicInteger();

            // 所有執行緒卡在同一道閘門上一起放行，製造真正的瞬間洪峰，
            // 而不是被執行緒池慢慢餵進去、變相串行化的假併發。
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch finishGate = new CountDownLatch(threads);

            try (ExecutorService pool = Executors.newFixedThreadPool(64)) {
                for (int i = 0; i < threads; i++) {
                    long userId = i;
                    pool.submit(() -> {
                        try {
                            startGate.await();
                            StockDeductionResult result = stockRepository.deduct(
                                    ACTIVITY_ID, userId, 1, PER_USER_LIMIT,
                                    "req-" + userId, "order-" + userId);
                            if (result.isSuccess()) {
                                succeeded.incrementAndGet();
                            } else if (result.outcome() == StockDeductionOutcome.SOLD_OUT) {
                                soldOut.incrementAndGet();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            finishGate.countDown();
                        }
                    });
                }
                startGate.countDown();
                assertThat(finishGate.await(60, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(succeeded.get()).as("成功扣減數必須等於庫存量，多一個就是超賣").isEqualTo(stock);
            assertThat(soldOut.get()).as("其餘請求都應收到售罄").isEqualTo(threads - stock);
            assertThat(stockRepository.availableStock(ACTIVITY_ID)).as("餘量不可為負").isZero();
        }

        @Test
        @DisplayName("同一使用者併發重複送出相同 requestId：只會扣一次")
        void idempotentUnderConcurrentDuplicateRequests() throws Exception {
            stockRepository.initialize(ACTIVITY_ID, 100, Duration.ofMinutes(10), true);
            int threads = 50;

            AtomicInteger succeeded = new AtomicInteger();
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch finishGate = new CountDownLatch(threads);

            try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            startGate.await();
                            if (stockRepository.deduct(ACTIVITY_ID, 7L, 1, PER_USER_LIMIT,
                                    "same-request-id", "order-x").isSuccess()) {
                                succeeded.incrementAndGet();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            finishGate.countDown();
                        }
                    });
                }
                startGate.countDown();
                assertThat(finishGate.await(30, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(succeeded.get()).as("同一 requestId 只能成功一次").isEqualTo(1);
            assertThat(stockRepository.availableStock(ACTIVITY_ID)).isEqualTo(99);
        }
    }

    @Nested
    @DisplayName("扣減語意")
    class DeductionSemantics {

        @Test
        @DisplayName("重送相同 requestId：回傳首次綁定的訂單號")
        void duplicateRequestReplaysOriginalOrderNo() {
            stockRepository.initialize(ACTIVITY_ID, 10, Duration.ofMinutes(10), true);
            stockRepository.deduct(ACTIVITY_ID, 1L, 1, PER_USER_LIMIT, "req-1", "ORDER-FIRST");

            StockDeductionResult replayed =
                    stockRepository.deduct(ACTIVITY_ID, 1L, 1, PER_USER_LIMIT, "req-1", "ORDER-SECOND");

            assertThat(replayed.outcome()).isEqualTo(StockDeductionOutcome.DUPLICATE_REQUEST);
            assertThat(replayed.orderNo()).isEqualTo("ORDER-FIRST");
            assertThat(stockRepository.availableStock(ACTIVITY_ID)).isEqualTo(9);
        }

        @Test
        @DisplayName("累計購買超過限購額度：拒絕，且不扣庫存")
        void rejectsWhenExceedingPerUserLimit() {
            stockRepository.initialize(ACTIVITY_ID, 10, Duration.ofMinutes(10), true);
            stockRepository.deduct(ACTIVITY_ID, 1L, 2, PER_USER_LIMIT, "req-1", "order-1");

            StockDeductionResult third =
                    stockRepository.deduct(ACTIVITY_ID, 1L, 1, PER_USER_LIMIT, "req-2", "order-2");

            assertThat(third.outcome()).isEqualTo(StockDeductionOutcome.USER_LIMIT_EXCEEDED);
            assertThat(stockRepository.availableStock(ACTIVITY_ID)).isEqualTo(8);
        }

        @Test
        @DisplayName("庫存未預熱：明確回報而非自動建鍵")
        void reportsUninitializedStock() {
            StockDeductionResult result =
                    stockRepository.deduct(9999L, 1L, 1, PER_USER_LIMIT, "req-1", "order-1");

            assertThat(result.outcome()).isEqualTo(StockDeductionOutcome.STOCK_NOT_INITIALIZED);
        }
    }

    @Nested
    @DisplayName("庫存補償")
    class Compensation {

        @Test
        @DisplayName("退庫後餘量與限購額度都要回復")
        void restoresStockAndUserQuota() {
            stockRepository.initialize(ACTIVITY_ID, 10, Duration.ofMinutes(10), true);
            stockRepository.deduct(ACTIVITY_ID, 1L, 2, PER_USER_LIMIT, "req-1", "order-1");

            assertThat(stockRepository.restore(ACTIVITY_ID, 1L, 2, "req-1")).isTrue();

            assertThat(stockRepository.availableStock(ACTIVITY_ID)).isEqualTo(10);
            // 額度回復後，同一使用者應能重新購買
            assertThat(stockRepository.deduct(ACTIVITY_ID, 1L, 2, PER_USER_LIMIT, "req-2", "order-2")
                    .isSuccess()).isTrue();
        }

        @Test
        @DisplayName("重複退庫只生效一次——補償排程與 DLQ 消費端可能同時發起")
        void restoreIsIdempotent() {
            stockRepository.initialize(ACTIVITY_ID, 10, Duration.ofMinutes(10), true);
            stockRepository.deduct(ACTIVITY_ID, 1L, 1, PER_USER_LIMIT, "req-1", "order-1");

            assertThat(stockRepository.restore(ACTIVITY_ID, 1L, 1, "req-1")).isTrue();
            assertThat(stockRepository.restore(ACTIVITY_ID, 1L, 1, "req-1")).isFalse();

            assertThat(stockRepository.availableStock(ACTIVITY_ID)).isEqualTo(10);
        }

        @Test
        @DisplayName("從未扣減過的 requestId：不退庫，避免憑空造出庫存")
        void doesNotRestoreUnknownRequest() {
            stockRepository.initialize(ACTIVITY_ID, 10, Duration.ofMinutes(10), true);

            assertThat(stockRepository.restore(ACTIVITY_ID, 1L, 5, "never-existed")).isFalse();
            assertThat(stockRepository.availableStock(ACTIVITY_ID)).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("預熱")
    class Warmup {

        @Test
        @DisplayName("非強制預熱不覆蓋既有餘量——否則每次補跑都會把賣掉的量加回去")
        void nonForcedWarmupPreservesSoldStock() {
            stockRepository.initialize(ACTIVITY_ID, 100, Duration.ofMinutes(10), true);
            stockRepository.deduct(ACTIVITY_ID, 1L, 2, PER_USER_LIMIT, "req-1", "order-1");

            stockRepository.initialize(ACTIVITY_ID, 100, Duration.ofMinutes(10), false);

            assertThat(stockRepository.availableStock(ACTIVITY_ID)).isEqualTo(98);
        }

        @Test
        @DisplayName("未預熱的活動餘量回 -1，與「賣完了」明確區分")
        void distinguishesUninitializedFromZero() {
            assertThat(stockRepository.availableStock(8888L)).isEqualTo(-1L);
        }
    }

    private static RedisScript<List> deductScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seckill_deduct.lua")));
        script.setResultType(List.class);
        return script;
    }

    private static RedisScript<Long> restoreScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seckill_restore.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
