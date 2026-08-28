package com.flashsale.api.adapter.in.web.interceptor;

import com.flashsale.api.adapter.in.web.security.AuthenticatedUserProvider;
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
 * 而且要早於參數綁定與反序列化。被擋掉的請求連 JSON 都不必解析。
 *
 * <p><b>限流維度取自令牌而非請求標頭</b>。這是關鍵差異：
 * 若沿用呼叫端自填的 {@code X-User-Id}，攻擊者只要每次換一個號碼就能完全繞過限流——
 * 那樣的限流器等於不存在。Interceptor 執行於 Security 過濾鏈之後，
 * 此時 SecurityContext 已填好，取到的身分是驗證過的。
 */
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
        // 未認證的請求交給 Security 過濾鏈處理（正常情況根本走不到這裡）。
        // 身分驗證不是限流器的職責，這裡不越權判斷。
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
