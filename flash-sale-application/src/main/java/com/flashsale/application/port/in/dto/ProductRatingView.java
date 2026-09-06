package com.flashsale.application.port.in.dto;

import com.flashsale.domain.review.ProductRating;

import java.math.BigDecimal;
import java.util.List;

/** 商品的評分摘要。 */
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
