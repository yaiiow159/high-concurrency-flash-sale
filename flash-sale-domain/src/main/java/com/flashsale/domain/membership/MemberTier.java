package com.flashsale.domain.membership;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 會員等級（ADR-0016 決策 4）。
 *
 * <h2>等級由「累計實付」決定，不是積分餘額</h2>
 *
 * <p>用餘額算等級會產生一個把整個機制反過來的激勵：
 * <b>使用者一花積分就降級</b>——而花積分正是我們希望他做的事。
 *
 * <h2>等級必須做點什麼</h2>
 *
 * <p>只給一個徽章的等級制度沒有作用。倍率是最便宜也最直接的作用，
 * 而且它讓「升級」有一個使用者自己算得出來的價值。
 */
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

    /**
     * 累計實付對應的等級。
     *
     * <p>由高往低找第一個達標的。用這個順序而不是逐級往上比，
     * 是因為新增等級時只要插進列舉的正確位置就好，不必改判斷邏輯。
     */
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

    /**
     * 這個等級消費指定金額可以拿到幾點。
     *
     * <p><b>無條件捨去。</b> 積分是送的，捨去的方向對商家有利而且不會有人客訴——
     * 而四捨五入會讓「消費 50 元拿 1 點」這種看起來像 bug 的結果出現。
     */
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

    /**
     * 距離下一級還差多少。已是最高級時回 0。
     *
     * <p>由後端算而不是讓前端拿門檻自己減——那個減法會在
     * 「已經超過門檻但還沒升級」這種狀態下算出負數，而畫面會直接顯示它。
     */
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
