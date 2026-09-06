package com.flashsale.application.port.in.dto;

import com.flashsale.domain.membership.MemberAccount;
import com.flashsale.domain.membership.MemberTier;

import java.math.BigDecimal;

/** 會員中心的主資料。 */
public record MemberProfileView(
        Long userId,
        String tier,
        String tierName,
        BigDecimal multiplier,
        long pointBalance,
        boolean inDebt,
        BigDecimal cumulativeSpend,
        String nextTier,
        String nextTierName,
        BigDecimal amountToNextTier,
        int progressToNextTier
) {

    public static MemberProfileView from(MemberAccount account) {
        MemberTier tier = account.tier();
        MemberTier next = tier.next();
        boolean highest = tier.isHighest();
        return new MemberProfileView(
                account.userId(),
                tier.name(),
                tier.displayName(),
                tier.multiplier(),
                account.pointBalance(),
                account.isInDebt(),
                account.cumulativeSpend(),
                // 已是最高級時回 null 而不是自己：畫面要顯示「已達最高等級」
                // 而不是「距離白金會員還差 0 元」
                highest ? null : next.name(),
                highest ? null : next.displayName(),
                account.amountToNextTier(),
                account.progressToNextTier());
    }
}
