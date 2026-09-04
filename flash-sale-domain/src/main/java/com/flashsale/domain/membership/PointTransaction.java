package com.flashsale.domain.membership;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/**
 * 一筆積分異動。
 *
 * <h2>存 delta 與 balanceAfter 兩個值</h2>
 *
 * <p>{@code balanceAfter} 是刻意的冗餘：它讓每一列<b>自我完備</b>——
 * 稽核一列時不必把前面所有列加一遍就知道當時的餘額，
 * 而「加一遍」在流水有幾萬列時是不可行的。
 *
 * <p>只存 delta 的話，「我上個月月底有多少點」這個問題
 * 需要一次全表掃描才答得出來。
 *
 * @param refNo 這筆異動的來源單號。與 {@code reason} 一起構成冪等鍵——
 *              訂單完成事件是至少一次投遞，重放不可以變成第二次入帳
 */
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
        if (delta == 0) {
            // 零異動只會讓流水變長而說不出任何事情
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "積分異動量不可為 0");
        }
    }

    public static PointTransaction of(Long userId, long delta, long balanceAfter,
                                      PointReason reason, String refNo, Instant now) {
        return new PointTransaction(null, userId, delta, balanceAfter, reason, refNo, now);
    }

    public boolean isEarning() {
        return delta > 0;
    }
}
