package com.flashsale.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

/**
 * Lua 腳本的載入與註冊。
 *
 * <p><b>腳本放在 {@code .lua} 檔案而非 Java 字串常數</b>，理由不只是美觀：
 * <ul>
 *   <li>IDE 與 linter 能提供語法檢查，字串裡的 Lua 錯字只有到執行期才會炸</li>
 *   <li>diff 可讀，review 時看得出改了哪一行邏輯</li>
 *   <li>可以直接餵給 {@code redis-cli --eval} 手動驗證，不必啟動整個應用</li>
 * </ul>
 *
 * <p>{@link DefaultRedisScript} 會先算好 SHA1 並優先以 {@code EVALSHA} 執行，
 * 每次呼叫只需傳送 40 個字元的雜湊而非整份腳本；腳本快取失效時會自動退回 {@code EVAL}。
 */
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
