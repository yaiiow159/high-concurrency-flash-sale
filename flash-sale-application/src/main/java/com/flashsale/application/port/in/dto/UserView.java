package com.flashsale.application.port.in.dto;

import com.flashsale.domain.identity.User;

import java.time.Instant;

/** 使用者資料的對外呈現。刻意不含密碼雜湊與任何憑證資訊。 */
public record UserView(
        Long userId,
        String email,
        String displayName,
        String role,
        String status,
        Instant createdAt
) {

    public static UserView from(User user) {
        return new UserView(
                user.id(),
                user.email().value(),
                user.displayName(),
                user.role().name(),
                user.status().name(),
                user.createdAt());
    }
}
