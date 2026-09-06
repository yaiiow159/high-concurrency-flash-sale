package com.flashsale.domain.membership;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/** 一筆積分異動。 */
public record PointTransaction(
        Long id,
        Long userId,
        long delta,
        long balanceAfter,
        PointReason reason,
        String refNo,
        Instant createdAt
) {

    public PointTransaction {
        Objects.requireNonNull(userId, "userId 不可為 null");
        Objects.requireNonNull(reason, "reason 不可為 null");
        Objects.requireNonNull(createdAt, "createdAt 不可為 null");
        if (refNo == null || refNo.isBlank()) {
            // 沒有來源單號的異動無法冪等，也無法追溯。人工調整也要給一個編號
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "積分異動必須有來源單號");
        }
        // delta 允許為 0：不足一點的訂單仍要計入累計消費，
        // 而累計消費的冪等鍵就在這張流水表上，所以那筆訂單必須有一列
    }

    public static PointTransaction of(Long userId, long delta, long balanceAfter,
                                      PointReason reason, String refNo, Instant now) {
        return new PointTransaction(null, userId, delta, balanceAfter, reason, refNo, now);
    }

    public boolean isEarning() {
        return delta > 0;
    }
}
