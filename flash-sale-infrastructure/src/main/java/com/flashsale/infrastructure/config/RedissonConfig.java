package com.flashsale.infrastructure.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Redisson 客戶端配置。 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database) {

        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://%s:%d".formatted(host, port))
                .setDatabase(database)
                // 鎖用的連線池刻意開小：鎖操作是低頻的，佔用大量連線只會排擠熱路徑。
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(8)
                .setPassword(password.isBlank() ? null : password);

        // 看門狗續期間隔：業務執行超過 lease 時自動續租，預設 30 秒足以覆蓋所有互斥場景。
        config.setLockWatchdogTimeout(30_000L);
        return Redisson.create(config);
    }
}
