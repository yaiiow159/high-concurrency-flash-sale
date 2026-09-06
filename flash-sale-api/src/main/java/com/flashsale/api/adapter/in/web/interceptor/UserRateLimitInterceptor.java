package com.flashsale.api.adapter.in.web.interceptor;

import com.flashsale.api.adapter.in.web.security.AuthenticatedUserProvider;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.infrastructure.adapter.out.ratelimit.UserRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 單一使用者的跨節點限流。 */
@Component
public class UserRateLimitInterceptor implements HandlerInterceptor {

    private static final String SCOPE = "seckill";

    private final UserRateLimiter rateLimiter;
    private final AuthenticatedUserProvider userProvider;

    public UserRateLimitInterceptor(UserRateLimiter rateLimiter, AuthenticatedUserProvider userProvider) {
        this.rateLimiter = rateLimiter;
        this.userProvider = userProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        return userProvider.currentUserId()
                .map(this::acquireOrReject)
                .orElse(true);
    }

    private boolean acquireOrReject(Long userId) {
        if (!rateLimiter.tryAcquire(SCOPE, String.valueOf(userId))) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
        return true;
    }
}
