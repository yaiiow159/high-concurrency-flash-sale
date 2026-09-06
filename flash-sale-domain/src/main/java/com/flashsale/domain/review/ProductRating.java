package com.flashsale.domain.review;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** 一件商品的評分聚合（ADR-0014 決策 2）。 */
public record ProductRating(Long productId, long ratingSum, int ratingCount, int[] counts) {

    private static final int SCALE = 1;

    public ProductRating {
        if (counts == null || counts.length != Rating.MAX + 1) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "評分分佈必須有 %d 個桶".formatted(Rating.MAX + 1));
        }
        counts = counts.clone();
    }

    /** 還沒有任何評價的商品。回這個而不是 null——畫面要顯示「尚無評價」而不是崩潰。 */
    public static ProductRating empty(Long productId) {
        return new ProductRating(productId, 0L, 0, new int[Rating.MAX + 1]);
    }

    public static ProductRating of(Long productId, long ratingSum, int ratingCount,
                                   int count1, int count2, int count3, int count4, int count5) {
        return new ProductRating(productId, ratingSum, ratingCount,
                new int[]{0, count1, count2, count3, count4, count5});
    }

    /** 平均分，取到小數一位。 */
    public BigDecimal average() {
        if (ratingCount == 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY);
        }
        return BigDecimal.valueOf(ratingSum)
                .divide(BigDecimal.valueOf(ratingCount), SCALE, RoundingMode.HALF_UP);
    }

    public boolean hasReviews() {
        return ratingCount > 0;
    }

    public int countOf(int stars) {
        return stars < Rating.MIN || stars > Rating.MAX ? 0 : counts[stars];
    }

    /** 某個星等佔的百分比，供長條圖使用。 */
    public int percentageOf(int stars) {
        if (ratingCount == 0) {
            return 0;
        }
        return Math.round(countOf(stars) * 100f / ratingCount);
    }

    /** 由高星到低星的分佈，這是評價區長條圖的呈現順序。 */
    public List<Map.Entry<Integer, Integer>> distributionDesc() {
        return java.util.stream.IntStream.rangeClosed(Rating.MIN, Rating.MAX)
                .boxed()
                .sorted(java.util.Comparator.reverseOrder())
                .map(stars -> Map.entry(stars, countOf(stars)))
                .toList();
    }

    @Override
    public int[] counts() {
        // record 的自動存取器會把內部陣列漏出去，改一下就能繞過建構子的驗證
        return counts.clone();
    }
}
