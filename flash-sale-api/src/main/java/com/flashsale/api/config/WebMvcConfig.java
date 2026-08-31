package com.flashsale.api.config;

import com.flashsale.api.adapter.in.web.interceptor.UserRateLimitInterceptor;
import com.flashsale.api.adapter.in.web.security.CurrentUserArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

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
    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    public WebMvcConfig(UserRateLimitInterceptor userRateLimitInterceptor,
                        CurrentUserArgumentResolver currentUserArgumentResolver) {
        this.userRateLimitInterceptor = userRateLimitInterceptor;
        this.currentUserArgumentResolver = currentUserArgumentResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(userRateLimitInterceptor)
                .addPathPatterns("/api/v1/seckill/orders")
                .order(0);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }
}
