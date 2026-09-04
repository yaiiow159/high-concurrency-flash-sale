package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ProductRatingView;
import com.flashsale.application.port.in.dto.ReviewView;
import com.flashsale.application.port.in.dto.ReviewableView;
import com.flashsale.domain.review.Review;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 商品評價（ADR-0014）。 */
public interface ReviewUseCase {

    ReviewView write(WriteReviewCommand command);

    ReviewView edit(EditReviewCommand command);

    /** 商品的評價列表，新到舊。 */
    List<ReviewView> byProduct(Long productId, int page, int size);

    /** 我寫過的評價。 */
    List<ReviewView> mine(Long userId, int page, int size);

    ProductRatingView ratingOf(Long productId);

    /**
     * 批次取評分，供商品列表使用。
     *
     * <p>存在的唯一理由是避免 N+1：列表一頁 24 件商品，逐件查就是 24 次往返。
     * 回傳的 Map <b>對每一個傳入的 ID 都有值</b>（沒有評價的補空聚合），
     * 讓呼叫端不必為「查不到」寫第二條路徑。
     */
    Map<Long, ProductRatingView> ratingsOf(List<Long> productIds);

    /**
     * 這張訂單現在能評什麼。
     *
     * <p>由後端算而不是讓前端自己比對訂單行與既有評價——
     * 前端再實作一次的話，症狀會是「畫面說可以評，送出卻被拒絕」。
     */
    ReviewableView reviewable(String orderNo, Long userId);

    /**
     * @param orderNo 評價綁定的是<b>訂單行</b>而不是使用者：同一個人可以買同一件
     *                商品兩次，那是兩次獨立的購買經驗，本來就該能各評一次
     */
    record WriteReviewCommand(Long userId, String orderNo, Long skuId,
                              int stars, String content) {

        public WriteReviewCommand {
            Objects.requireNonNull(userId, "userId 不可為 null");
            Objects.requireNonNull(skuId, "skuId 不可為 null");
            if (orderNo == null || orderNo.isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER, "orderNo 不可為空");
            }
            content = normalizeContent(content);
        }
    }

    /**
     * @param reviewId 要改哪一則。<b>沒有舊評分欄位</b>——舊評分由伺服器當場讀出來，
     *                 呼叫端若能宣告舊評分是多少，它就能把商品的平均分改成任何值
     */
    record EditReviewCommand(Long userId, Long reviewId, int stars, String content) {

        public EditReviewCommand {
            Objects.requireNonNull(userId, "userId 不可為 null");
            Objects.requireNonNull(reviewId, "reviewId 不可為 null");
            content = normalizeContent(content);
        }
    }

    /**
     * 內容的共用正規化。
     *
     * <p>去掉前後空白後才驗長度：一則「一千個空白 + 好」的評價
     * 在資料庫裡佔滿欄位，在畫面上卻是空的。
     */
    private static String normalizeContent(String candidate) {
        String trimmed = candidate == null ? "" : candidate.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "評價內容不可為空");
        }
        if (trimmed.length() > Review.MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "評價內容不可超過 %d 字".formatted(Review.MAX_CONTENT_LENGTH));
        }
        return trimmed;
    }
}
