package com.flashsale.infrastructure.config;

import io.lettuce.core.tracing.MicrometerTracing;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 讓 Lettuce（StringRedisTemplate 背後）替每次 Redis 往返產生 span（ADR-0029）。
 * 秒殺 trace 裡最重要的一段就是 Lua 扣減那幾毫秒；少了它，trace 只剩 HTTP 與 Kafka。
 * Redisson 走自己的連線，不在這裡——它只用在分散式鎖，不在熱路徑上。
 *
 * 條件用 class 而不是 bean：ObservationRegistry 由自動設定較晚註冊，
 * 以 bean 為條件時這個設定類在評估當下看不到它，customizer 就永遠不會建立。
 */
@Configuration
@ConditionalOnClass(MicrometerTracing.class)
public class LettuceTracingConfig {

    @Bean
    public ClientResourcesBuilderCustomizer lettuceTracingCustomizer(ObservationRegistry observationRegistry) {
        // includeCommandArgsInSpanTags=false：Lua 腳本與 key 會進 tag，但參數（userId、requestId）不進
        return builder -> builder.tracing(new MicrometerTracing(observationRegistry, "redis", false));
    }
}
