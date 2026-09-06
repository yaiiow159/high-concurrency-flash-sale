package com.flashsale.api.adapter.in.web.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 從 SecurityContext 取出當前使用者 ID。 */
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
