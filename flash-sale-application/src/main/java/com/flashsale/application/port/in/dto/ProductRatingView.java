package com.flashsale.application.port.in.dto;

import com.flashsale.domain.review.ProductRating;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品的評分摘要。
 *
 * <p>分佈的百分比由<b>後端</b>算好給前端。讓前端拿 count 自己除的話，
 * 兩邊各算一次遲早會有一邊的四捨五入不同，而那會表現成
 * 「長條圖加起來不是 100%」——一個沒有人會回報但每個人都看得出來的瑕疵。
 *
 * @param average     平均分，小數一位。沒有評價時是 0，
 *                    畫面靠 {@code count == 0} 決定顯示「尚無評價」而不是「0 分」
 * @param distribution 由高星到低星，那是評價區長條圖的呈現順序
 */
public record ProductRatingView(
        Long productId,
        BigDecimal average,
        int count,
        List<Bucket> distribution
) {

    /** @param percentage 已經算好的百分比（0–100），前端直接拿去畫長條寬度 */
    public record Bucket(int stars, int count, int percentage) {
    }

    public static ProductRatingView from(ProductRating rating) {
        List<Bucket> buckets = rating.distributionDesc().stream()
                .map(entry -> new Bucket(entry.getKey(), entry.getValue(),
                        rating.percentageOf(entry.getKey())))
                .toList();
        return new ProductRatingView(rating.productId(), rating.average(),
                rating.ratingCount(), buckets);
    }
}
