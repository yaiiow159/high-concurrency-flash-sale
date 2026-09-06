package com.flashsale.application.port.in.dto;

import com.flashsale.domain.review.Review;

import java.time.Instant;

/** 一則評價。 */
public record ReviewView(
        Long reviewId,
        Long productId,
        Long skuId,
        String authorName,
        int stars,
        String content,
        Instant createdAt,
        boolean edited,
        boolean editable
) {

    /**
     * @param now 由呼叫端傳入，<b>不在這裡讀時鐘</b>（鐵則 9）。
     * 「修改窗口最後一秒還能不能改」因此可以寫成一個固定的測試
     */
    public static ReviewView from(Review review, Instant now) {
        return new ReviewView(review.id(), review.productId(), review.skuId(),
                review.authorName(), review.rating().stars(), review.content(),
                review.createdAt(), review.isEdited(),
                // editable 由聚合根判斷，而不是讓前端拿 createdAt 自己算七天——
                // 前端算出來的版本會與伺服器的判斷在時區與時鐘偏移上分岔，
                // 而那表現成「畫面顯示可以改，送出卻被拒絕」
                review.isEditableAt(now));
    }
}
