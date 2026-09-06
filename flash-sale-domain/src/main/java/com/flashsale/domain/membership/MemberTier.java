package com.flashsale.domain.membership;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 會員等級（ADR-0016 決策 4）。 */
public enum MemberTier {

    BRONZE("一般會員", 0, "1.0"),
    SILVER("銀卡會員", 10_000, "1.2"),
    GOLD("金卡會員", 50_000, "1.5"),
    PLATINUM("白金會員", 200_000, "2.0");

    /** 每消費多少元累積一點。放在這裡而不是設定檔——改它等於改所有人的權益。 */
    public static final BigDecimal SPEND_PER_POINT = BigDecimal.valueOf(100);

    private final String displayName;
    private final long threshold;
    private final BigDecimal multiplier;

    MemberTier(String displayName, long threshold, String multiplier) {
        this.displayName = displayName;
        this.threshold = threshold;
        this.multiplier = new BigDecimal(multiplier);
    }

    /** 累計實付對應的等級。 */
    public static MemberTier forSpend(BigDecimal cumulativeSpend) {
        BigDecimal spend = cumulativeSpend == null ? BigDecimal.ZERO : cumulativeSpend;
        MemberTier[] tiers = values();
        for (int i = tiers.length - 1; i >= 0; i--) {
            if (spend.compareTo(BigDecimal.valueOf(tiers[i].threshold)) >= 0) {
                return tiers[i];
            }
        }
        return BRONZE;
    }

    /** 這個等級消費指定金額可以拿到幾點。 */
    public long pointsFor(BigDecimal paidAmount) {
        if (paidAmount == null || paidAmount.signum() <= 0) {
            return 0L;
        }
        return paidAmount.multiply(multiplier)
                .divide(SPEND_PER_POINT, 0, RoundingMode.DOWN)
                .longValue();
    }

    /** 下一級；已是最高級時回自己。 */
    public MemberTier next() {
        MemberTier[] tiers = values();
        return this == tiers[tiers.length - 1] ? this : tiers[ordinal() + 1];
    }

    public boolean isHighest() {
        return this == values()[values().length - 1];
    }

    /** 距離下一級還差多少。已是最高級時回 0。 */
    public BigDecimal amountToNextTier(BigDecimal cumulativeSpend) {
        if (isHighest()) {
            return BigDecimal.ZERO;
        }
        BigDecimal spend = cumulativeSpend == null ? BigDecimal.ZERO : cumulativeSpend;
        BigDecimal gap = BigDecimal.valueOf(next().threshold).subtract(spend);
        return gap.signum() < 0 ? BigDecimal.ZERO : gap;
    }

    public String displayName() {
        return displayName;
    }

    public long threshold() {
        return threshold;
    }

    public BigDecimal multiplier() {
        return multiplier;
    }
}
