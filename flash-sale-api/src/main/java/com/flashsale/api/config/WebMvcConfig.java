package com.flashsale.api.config;

import com.flashsale.api.adapter.in.web.interceptor.UserRateLimitInterceptor;
import com.flashsale.api.adapter.in.web.security.CurrentUserArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Web 層配置。 */
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
