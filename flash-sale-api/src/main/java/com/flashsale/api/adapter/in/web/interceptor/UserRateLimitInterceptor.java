package com.flashsale.api.adapter.in.web.interceptor;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.infrastructure.adapter.out.ratelimit.UserRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 單一使用者的跨節點限流。
 *
 * <p>擋的是「同一個帳號用腳本狂送請求」這種行為。Resilience4j 的單機限流對此無能為力——
 * 攻擊者只要把請求打散到不同節點就能繞過。
 *
 * <p>寫成 Interceptor 而非 AOP 切面，是因為限流應該在<b>進入業務邏輯之前</b>就攔下，
 * 而且要早於參數綁定與反序列化。被擋掉的請求連 JSON 都不必解析，這才是限流該有的成本。
 */
@Component
public class UserRateLimitInterceptor implements HandlerInterceptor {

    private static final String SCOPE = "seckill";
    private static final String USER_HEADER = "X-User-Id";

    private final UserRateLimiter rateLimiter;

    public UserRateLimitInterceptor(UserRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getHeader(USER_HEADER);
        if (userId == null || userId.isBlank()) {
            // 身分驗證不是限流器的職責，交給後面的 @RequestHeader 產生標準錯誤回應。
            return true;
        }
        if (!rateLimiter.tryAcquire(SCOPE, userId)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
        return true;
    }
}
