package com.flashsale.application.service;

import com.flashsale.application.port.in.RatingReconciliationUseCase;
import com.flashsale.application.port.in.dto.RatingReconciliation;
import com.flashsale.application.port.out.ReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 評分聚合對帳（ADR-0014 的「後果」欄要求）。
 *
 * <h2>這是唯一一個可以自動修復的對帳</h2>
 *
 * <p>庫存與積分的對帳都<b>只讀不修</b>，理由是偏差的成因分不出來：
 * 積分餘額多了，可能是流水漏寫、也可能是有人直接改了餘額，
 * 而兩者的正確修法相反。
 *
 * <p>評分不一樣：{@code review} 表是原始事實，{@code product_rating}
 * 只是它的統計。重算不需要任何猜測——真實來源是明確的。
 *
 * <p>但預設仍然關閉。看過差異再決定要不要修，是唯一安全的順序，
 * 而且偏差的<b>存在</b>本身就是一個訊號：有東西繞過了 {@code ReviewService}。
 * 只修數字不查原因，下週會再看到一次。
 */
@Service
public class RatingReconciliationService implements RatingReconciliationUseCase {

    private static final Logger log = LoggerFactory.getLogger(RatingReconciliationService.class);

    private final ReviewRepository reviewRepository;

    public RatingReconciliationService(ReviewRepository reviewRepository) {
        this.reviewRepository = reviewRepository;
    }

    @Override
    @Transactional
    public RatingReconciliation reconcile(boolean repair) {
        List<ReviewRepository.RatingDrift> drifts = reviewRepository.findRatingDrifts();

        if (repair) {
            for (ReviewRepository.RatingDrift drift : drifts) {
                // 逐筆修而不是一次全部——單一商品修失敗不該讓其餘的也停下來。
                // 這與搜尋索引對帳的修復同一個判斷
                try {
                    reviewRepository.recomputeRating(drift.productId());
                    log.info("已重算商品 {} 的評分聚合：{} 則 → {} 則",
                            drift.productId(), drift.storedCount(), drift.actualCount());
                } catch (RuntimeException e) {
                    log.error("重算商品 {} 的評分聚合失敗，其餘繼續", drift.productId(), e);
                }
            }
        } else if (!drifts.isEmpty()) {
            log.warn("評分對帳發現 {} 個商品的聚合與評價不符", drifts.size());
        }

        return RatingReconciliation.of(drifts.stream()
                .map(drift -> new RatingReconciliation.Drift(drift.productId(),
                        drift.actualCount(), drift.actualSum(),
                        drift.storedCount(), drift.storedSum()))
                .toList());
    }
}
