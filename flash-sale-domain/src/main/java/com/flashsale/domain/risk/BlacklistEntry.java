package com.flashsale.domain.risk;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/** 黑名單：與停權不同，被列入的人仍可登入、逛、下一般訂單，只是拿不到搶購資格。 */
public record BlacklistEntry(Long userId, String reason, Long createdBy, Instant createdAt, Instant expiresAt) {

    private static final int MAX_REASON_LENGTH = 200;

    public BlacklistEntry {
        Objects.requireNonNull(userId, "userId 不可為 null");
        Objects.requireNonNull(createdAt, "createdAt 不可為 null");
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "列入黑名單必須填寫原因");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "原因不可超過 200 字");
        }
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "到期時間必須晚於現在");
        }
        reason = reason.trim();
    }

    /** expiresAt 為 null 代表永久。 */
    public boolean isActiveAt(Instant now) {
        return expiresAt == null || now.isBefore(expiresAt);
    }
}
