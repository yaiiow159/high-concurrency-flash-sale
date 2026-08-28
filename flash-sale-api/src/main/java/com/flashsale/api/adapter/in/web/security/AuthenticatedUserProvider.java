package com.flashsale.api.adapter.in.web.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 從 SecurityContext 取出當前使用者 ID。
 *
 * <p>使用者 ID 取自標準的 {@code sub} claim。刻意<b>不</b>另外用自訂 claim（如 {@code userId}）：
 * {@code sub} 是 RFC 7519 定義的主體識別，任何符合規範的 IdP 都會提供，
 * 未來換成外部 IdP 時不必要求對方配合加欄位。
 *
 * <p>抽成獨立元件（而非塞進 ArgumentResolver）是為了讓 Interceptor 也能用同一套解析邏輯——
 * 兩處若各自實作，遲早會出現「限流認的使用者」與「下單認的使用者」不一致的詭異問題。
 */
@Component
public class AuthenticatedUserProvider {

    private static final Logger log = LoggerFactory.getLogger(AuthenticatedUserProvider.class);

    /** 目前請求的使用者 ID；未認證或 {@code sub} 非數字時回傳 empty。 */
    public Optional<Long> currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        return parseSubject(jwt.getSubject());
    }

    private Optional<Long> parseSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(subject));
        } catch (NumberFormatException e) {
            // 令牌簽章有效但 sub 不是本系統認得的格式——多半是接錯了 IdP。
            // 記 warn 而非 error：這是設定問題，不是程式錯誤，且可能被大量重複觸發。
            log.warn("令牌的 sub claim 不是合法的 userId: {}", subject);
            return Optional.empty();
        }
    }
}
