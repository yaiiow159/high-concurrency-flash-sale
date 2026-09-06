package com.flashsale.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

/** Lua 腳本的載入與註冊。 */
@Configuration
public class RedisScriptConfig {

    @Bean
    public RedisScript<List> seckillDeductScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seckill_deduct.lua")));
        script.setResultType(List.class);
        return script;
    }

    /** 令牌桶限流腳本，回傳 { allowed, remaining }。 */
    @Bean
    public RedisScript<List> rateLimitScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/rate_limit.lua")));
        script.setResultType(List.class);
        return script;
    }

    @Bean
    public RedisScript<Long> seckillRestoreScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seckill_restore.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
