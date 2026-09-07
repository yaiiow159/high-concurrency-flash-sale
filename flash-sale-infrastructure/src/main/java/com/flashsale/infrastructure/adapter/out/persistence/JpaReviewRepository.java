package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.ReviewRepository;
import com.flashsale.domain.review.ProductRating;
import com.flashsale.domain.review.Rating;
import com.flashsale.domain.review.Review;
import com.flashsale.infrastructure.adapter.out.persistence.entity.ProductRatingEntity;
import com.flashsale.infrastructure.adapter.out.persistence.entity.ReviewEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.ProductRatingJpaRepository;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.ReviewJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 評價與評分聚合的 JPA 實作。 */
@Repository
public class JpaReviewRepository implements ReviewRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaReviewRepository.class);

    private final ReviewJpaRepository reviewJpaRepository;
    private final ProductRatingJpaRepository ratingJpaRepository;

    public JpaReviewRepository(ReviewJpaRepository reviewJpaRepository,
                               ProductRatingJpaRepository ratingJpaRepository) {
        this.reviewJpaRepository = reviewJpaRepository;
        this.ratingJpaRepository = ratingJpaRepository;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Optional<Review> saveIfAbsent(Review review) {
        try {
            ReviewEntity saved = reviewJpaRepository.saveAndFlush(toEntity(review));
            return Optional.of(toDomain(saved));
        } catch (DataIntegrityViolationException duplicate) {
            log.debug("訂單 {} 的 SKU {} 已有評價", review.orderNo(), review.skuId());
            return Optional.empty();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Review> findById(Long reviewId) {
        return reviewJpaRepository.findById(reviewId).map(JpaReviewRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Review> findByOrderAndSku(String orderNo, Long skuId) {
        return reviewJpaRepository.findByOrderNoAndSkuId(orderNo, skuId)
                .map(JpaReviewRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Review> findByProductId(Long productId, int limit, int offset) {
        return reviewJpaRepository
                .findByProduct(productId, Pageables.of(limit, offset)).stream()
                .map(JpaReviewRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Review> findByUserId(Long userId, int limit, int offset) {
        return reviewJpaRepository
                .findByUser(userId, Pageables.of(limit, offset)).stream()
                .map(JpaReviewRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findReviewedSkuIds(String orderNo) {
        return reviewJpaRepository.findReviewedSkuIds(orderNo);
    }

    @Override
    @Transactional
    public void update(Review review) {
        ReviewEntity entity = reviewJpaRepository.findById(review.id())
                .orElseThrow(() -> new IllegalStateException("評價不存在 id=" + review.id()));
        entity.applyEdit(review.rating().stars(), review.content(), review.updatedAt());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void addRating(Long productId, Rating rating) {
        if (ratingJpaRepository.incrementTotals(productId, rating.stars()) == 0) {
            ensureRow(productId);
            ratingJpaRepository.incrementTotals(productId, rating.stars());
        }
        adjustBucket(productId, rating.stars(), 1);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void replaceRating(Long productId, Rating oldRating, Rating newRating) {
        int delta = newRating.stars() - oldRating.stars();
        if (delta != 0) {
            ratingJpaRepository.adjustSum(productId, delta);
        }
        if (oldRating.stars() != newRating.stars()) {
            adjustBucket(productId, oldRating.stars(), -1);
            adjustBucket(productId, newRating.stars(), 1);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ProductRating findRating(Long productId) {
        return ratingJpaRepository.findById(productId)
                .map(JpaReviewRepository::toDomain)
                .orElseGet(() -> ProductRating.empty(productId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ProductRating> findRatings(List<Long> productIds) {
        if (productIds.isEmpty()) {
            // 空集合會產生 `in ()` 這種在部分資料庫上非法的 SQL
            return Map.of();
        }
        return ratingJpaRepository.findAllByProductIds(productIds).stream()
                .collect(Collectors.toMap(ProductRatingEntity::getProductId,
                        JpaReviewRepository::toDomain, (first, second) -> first));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<RatingDrift> findRatingDrifts() {
        return java.util.stream.Stream.concat(
                        ratingJpaRepository.findRatingDrifts().stream(),
                        ratingJpaRepository.findMissingAggregates().stream())
                .map(row -> new RatingDrift(row.getProductId(), row.getActualCount(),
                        row.getActualSum(), row.getStoredCount(), row.getStoredSum()))
                .toList();
    }

    @Override
    @Transactional
    public void recomputeRating(Long productId) {
        // 聚合列不存在時先補一列，否則 UPDATE 影響 0 列而修復靜靜地失敗
        ensureRow(productId);
        ratingJpaRepository.recomputeFromReviews(productId);
    }

    private void ensureRow(Long productId) {
        try {
            ratingJpaRepository.saveAndFlush(new ProductRatingEntity(productId));
        } catch (DataIntegrityViolationException concurrent) {
            // 另一個「第一則評價」搶先補好了。這正是我們要的結果
            log.debug("商品 {} 的評分列已由並行請求建立", productId);
        }
    }

    /** 分佈桶的增減。 */
    private void adjustBucket(Long productId, int stars, int delta) {
        switch (stars) {
            case 1 -> ratingJpaRepository.adjustCount1(productId, delta);
            case 2 -> ratingJpaRepository.adjustCount2(productId, delta);
            case 3 -> ratingJpaRepository.adjustCount3(productId, delta);
            case 4 -> ratingJpaRepository.adjustCount4(productId, delta);
            case 5 -> ratingJpaRepository.adjustCount5(productId, delta);
            default -> throw new IllegalStateException("星等超出範圍: " + stars);
        }
    }

    private static ReviewEntity toEntity(Review review) {
        return new ReviewEntity(review.productId(), review.skuId(), review.orderNo(),
                review.userId(), review.authorName(), review.rating().stars(),
                review.content(), review.createdAt(), review.updatedAt());
    }

    private static Review toDomain(ReviewEntity entity) {
        return Review.restore(entity.getId(), entity.getProductId(), entity.getSkuId(),
                entity.getOrderNo(), entity.getUserId(), entity.getAuthorName(),
                Rating.of(entity.getRating()), entity.getContent(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static ProductRating toDomain(ProductRatingEntity entity) {
        return ProductRating.of(entity.getProductId(), entity.getRatingSum(),
                entity.getRatingCount(), entity.getCount1(), entity.getCount2(),
                entity.getCount3(), entity.getCount4(), entity.getCount5());
    }
}
