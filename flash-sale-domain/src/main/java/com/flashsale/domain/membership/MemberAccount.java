package com.flashsale.domain.membership;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 會員帳戶：積分餘額與累計消費。
 *
 * <h2>這個型別不做寫入</h2>
 *
 * <p>餘額的變動一律走條件式增量 UPDATE（見 {@code MemberAccountJpaRepository}）。
 * 在這裡提供 {@code add()} 之類的方法會誘使呼叫端做「讀出來、加、寫回去」，
 * 而兩個並行的入帳會吃掉其中一筆。這與 {@code ProductRating} 是同一個判斷。
 *
 * <h2>等級是導出值，不是狀態</h2>
 *
 * <p>{@code tier} 欄位存在資料庫裡只是為了讓查詢不必每次重算，
 * <b>真實來源永遠是 {@code cumulativeSpend}</b>。因此這裡的 {@link #tier()}
 * 當場推導而不是回傳存下來的那個值——存下來的可能因為門檻調整而過時。
 */
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

    /**
     * 當下的等級。
     *
     * <p><b>從累計消費推導，不讀資料庫裡的 tier 欄位。</b>
     * 那個欄位是快取，而門檻是會被調整的——調完之後所有人的等級
     * 應該立刻反映新規則，而不是等到下一次消費才更新。
     */
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

    /**
     * 目前等級區間的完成度（0–100），供進度條使用。
     *
     * <p>由後端算：前端拿兩個門檻自己內插會在「剛升級」與「已達頂級」
     * 兩個邊界算出 NaN 或超過 100 的值，而那會直接畫成一條超出容器的長條。
     */
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
