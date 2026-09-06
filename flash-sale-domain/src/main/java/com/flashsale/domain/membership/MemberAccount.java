package com.flashsale.domain.membership;

import java.math.BigDecimal;
import java.util.Objects;

/** 會員帳戶：積分餘額與累計消費。 */
public record MemberAccount(
        Long userId,
        long pointBalance,
        BigDecimal cumulativeSpend
) {

    public MemberAccount {
        Objects.requireNonNull(userId, "userId 不可為 null");
        cumulativeSpend = cumulativeSpend == null ? BigDecimal.ZERO : cumulativeSpend;
    }

    /** 還沒有任何消費的新會員。回這個而不是 null——每個登入的人都該看得到自己的會員頁。 */
    public static MemberAccount fresh(Long userId) {
        return new MemberAccount(userId, 0L, BigDecimal.ZERO);
    }

    /** 當下的等級。 */
    public MemberTier tier() {
        return MemberTier.forSpend(cumulativeSpend);
    }

    /** 餘額是負的代表退貨扣回時他已經把點花掉了。這是真實的債務，不是錯誤狀態。 */
    public boolean isInDebt() {
        return pointBalance < 0;
    }

    /** 距離下一級還差多少。 */
    public BigDecimal amountToNextTier() {
        return tier().amountToNextTier(cumulativeSpend);
    }

    /** 目前等級區間的完成度（0–100），供進度條使用。 */
    public int progressToNextTier() {
        MemberTier current = tier();
        if (current.isHighest()) {
            return 100;
        }
        BigDecimal from = BigDecimal.valueOf(current.threshold());
        BigDecimal to = BigDecimal.valueOf(current.next().threshold());
        BigDecimal span = to.subtract(from);
        if (span.signum() <= 0) {
            return 100;
        }
        BigDecimal done = cumulativeSpend.subtract(from);
        int percentage = done.multiply(BigDecimal.valueOf(100))
                .divide(span, 0, java.math.RoundingMode.DOWN)
                .intValue();
        return Math.clamp(percentage, 0, 100);
    }
}
