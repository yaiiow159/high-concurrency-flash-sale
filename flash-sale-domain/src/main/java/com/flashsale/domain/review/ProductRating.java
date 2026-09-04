package com.flashsale.domain.review;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 一件商品的評分聚合（ADR-0014 決策 2）。
 *
 * <h2>存的是總和與筆數，不是平均值</h2>
 *
 * <p>存平均值是最直覺、也最錯的選擇：新增一則評價要重算平均，
 * 而重算需要筆數——**存導出值就重建不出原始事實**。
 * 這與庫存流水記 {@code availableDelta} 與 {@code allocatedDelta}
 * 兩個增減量而不是單一 quantity 是同一件事。
 *
 * <p>分佈（{@code counts[1..5]}）同樣存下來而不是即時算：
 * 電商的評價區一定要有那條長條圖，而它不該靠掃全表產生。
 *
 * <h2>這個型別本身不做寫入</h2>
 *
 * <p>聚合的更新是一句條件式增量 UPDATE（見 {@code ProductRatingJpaRepository}）。
 * 在這裡提供 {@code plus()} 之類的方法會誘使呼叫端做
 * 「讀出來、加、寫回去」，而兩個人同時評價同一件商品時會有一則被吃掉。
 * 這個型別只負責<b>讀出來之後怎麼呈現</b>。
 *
 * @param counts 各星等的則數，索引 1..5；索引 0 永遠是 0，只為了讓
 *               {@code counts[stars]} 讀起來就是它的意思
 */
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

    /**
     * 平均分，取到小數一位。
     *
     * <p>沒有評價時回 0 而不是拋例外或回 null：
     * 「這件商品還沒有人評價」是預期狀態，不是錯誤。
     * 畫面靠 {@link #hasReviews()} 決定要顯示星等還是「尚無評價」。
     *
     * <p>四捨五入而非捨去——這裡沒有錢的方向性問題，
     * 而 4.25 顯示成 4.2 會讓使用者覺得系統在扣分。
     */
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

    /**
     * 某個星等佔的百分比，供長條圖使用。
     *
     * <p>由後端算而不是讓前端拿 count 自己除：
     * 兩邊各算一次，遲早會有一邊的四捨五入不同，
     * 而那會表現成「長條加起來不是 100%」。
     */
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
