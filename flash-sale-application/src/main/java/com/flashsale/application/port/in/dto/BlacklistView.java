package com.flashsale.application.port.in.dto;

import com.flashsale.domain.risk.BlacklistEntry;

import java.time.Instant;

public record BlacklistView(
        Long userId,
        String email,
        String displayName,
        String reason,
        Long createdBy,
        Instant createdAt,
        Instant expiresAt
) {

    public static BlacklistView from(BlacklistEntry entry, String email, String displayName) {
        return new BlacklistView(entry.userId(), email, displayName, entry.reason(),
                entry.createdBy(), entry.createdAt(), entry.expiresAt());
    }
}
