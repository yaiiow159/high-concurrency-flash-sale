package com.flashsale.api.config;

import com.flashsale.api.adapter.in.web.interceptor.UserRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 層配置。
 *
 * <p>限流攔截器<b>只掛在搶購端點</b>，不掛在查詢端點：
 * 前端在等待訂單建立時會高頻輪詢查詢 API，若一併限流，
 * 使用者會在最焦慮的時候看到「請求過於頻繁」。
 * 限流的對象應該是會消耗庫存的寫入操作。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final UserRateLimitInterceptor userRateLimitInterceptor;

    public WebMvcConfig(UserRateLimitInterceptor userRateLimitInterceptor) {
        this.userRateLimitInterceptor = userRateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(userRateLimitInterceptor)
                .addPathPatterns("/api/v1/seckill/orders")
                .order(0);
    }
}
