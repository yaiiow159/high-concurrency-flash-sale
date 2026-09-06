package com.flashsale.infrastructure.id;

import com.flashsale.infrastructure.config.FlashSaleProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 節點編號互斥——對著<b>真實的 Redis</b> 驗證。
 *
 * <p>這條防線要擋的是「多開一個副本卻忘記改 {@code snowflake.node-id}」，
 * 而它的症狀是尖峰時訂單唯一索引開始爆。
 * mock 掉 Redis 就等於 mock 掉宣告本身，測試會全綠而防線根本不存在。
 */
@Testcontainers
@DisplayName("Snowflake 節點編號互斥")
class SnowflakeNodeIdGuardTest {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getFirstMappedPort()));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        REDIS.stop();
    }

    @BeforeEach
    void flush() {
        redisTemplate.delete(redisTemplate.keys("seckill:node-id:*"));
    }

    private static SnowflakeNodeIdGuard guard(long nodeId) {
        return new SnowflakeNodeIdGuard(redisTemplate,
                new FlashSaleProperties(null, null, null,
                        new FlashSaleProperties.Snowflake(nodeId), null, null));
    }

    @Nested
    @DisplayName("宣告")
    class Claiming {

        @Test
        @DisplayName("第一個節點宣告成功")
        void firstNodeSucceeds() {
            assertThatCode(() -> guard(7L)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("第二個節點用同一個編號會拒絕啟動")
        void duplicateNodeIdIsRejected() {
            guard(7L);

            // 這正是「多開一個副本卻忘記改設定」的樣子。
            // 放行的話兩個節點會在同一毫秒發出相同的識別碼，
            // 而症狀要到尖峰時才會以唯一索引衝突的形式出現
            assertThatThrownBy(() -> guard(7L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已被另一個存活中的節點佔用");
        }

        @Test
        @DisplayName("不同編號互不影響")
        void differentNodeIdsCoexist() {
            guard(1L);
            assertThatCode(() -> guard(2L)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("交還之後同一個編號可以立刻重用")
        void releasedNodeIdIsReusable() {
            // 正常關機要能立刻用同一個編號重啟——
            // 否則每次重新部署都要等租期過完
            SnowflakeNodeIdGuard first = guard(3L);
            first.release();

            assertThatCode(() -> guard(3L)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("租約")
    class Lease {

        @Test
        @DisplayName("宣告帶 TTL，不是永久佔用")
        void claimExpires() {
            guard(5L);

            // 永久鍵的話，節點被 kill -9 之後那個編號就再也沒人能用，
            // 而「重啟一個當掉的服務」是維運最常做的動作
            Long ttl = redisTemplate.getExpire("seckill:node-id:5");
            assertThat(ttl).isNotNull().isPositive();
        }

        @Test
        @DisplayName("續租會把 TTL 推回去")
        void renewExtendsTtl() {
            SnowflakeNodeIdGuard held = guard(6L);
            redisTemplate.expire("seckill:node-id:6", java.time.Duration.ofSeconds(5));

            held.renew();

            assertThat(redisTemplate.getExpire("seckill:node-id:6")).isGreaterThan(5L);
        }

        @Test
        @DisplayName("租約被別人接手後，不再續租別人的鍵")
        void doesNotRenewSomeoneElsesClaim() {
            SnowflakeNodeIdGuard stale = guard(8L);

            // 模擬「租約過期 → 別的節點接手」：值換成別人的 token
            redisTemplate.opsForValue().set("seckill:node-id:8", "another-instance",
                    java.time.Duration.ofSeconds(30));

            stale.renew();

            // 續租若不先比對，這裡會變成把別人的租約蓋掉——
            // 於是兩個節點都以為自己持有這個編號，防線等於沒有
            assertThat(redisTemplate.opsForValue().get("seckill:node-id:8"))
                    .isEqualTo("another-instance");
        }

        @Test
        @DisplayName("交還時不會刪掉別人的鍵")
        void doesNotReleaseSomeoneElsesClaim() {
            SnowflakeNodeIdGuard stale = guard(9L);
            redisTemplate.opsForValue().set("seckill:node-id:9", "another-instance",
                    java.time.Duration.ofSeconds(30));

            stale.release();

            assertThat(redisTemplate.opsForValue().get("seckill:node-id:9"))
                    .isEqualTo("another-instance");
        }
    }
}
