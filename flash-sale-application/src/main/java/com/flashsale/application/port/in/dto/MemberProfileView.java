package com.flashsale.application.port.in.dto;

import com.flashsale.domain.membership.MemberAccount;
import com.flashsale.domain.membership.MemberTier;

import java.math.BigDecimal;

/**
 * 會員中心的主資料。
 *
 * <p><b>升級進度由後端算。</b> 前端拿兩個門檻自己內插，會在
 * 「剛升級」與「已達頂級」兩個邊界算出 NaN 或超過 100 的值，
 * 而那會直接畫成一條超出容器的長條。
 *
 * @param tier          目前等級的代號，供前端對應樣式
 * @param multiplier    這個等級的積分回饋倍率。顯示出來讓「升級」有一個
 *                      使用者自己算得出來的價值——只給徽章的等級制度沒有作用
 * @param inDebt        餘額為負。退貨扣回時使用者已經把點花掉了，
 *                      那是真實的債務而不是錯誤狀態，畫面要說清楚
 */
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
