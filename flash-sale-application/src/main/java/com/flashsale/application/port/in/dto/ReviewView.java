package com.flashsale.application.port.in.dto;

import com.flashsale.domain.review.Review;

import java.time.Instant;

/**
 * 一則評價。
 *
 * <p><b>沒有 userId。</b> 評價列表是公開的，帶上使用者 ID 等於讓任何人
 * 把某個 ID 的所有評價串起來看——那是一份消費紀錄。
 * 需要判斷「這則是不是我寫的」時，由 {@code mine} 那條路徑回答。
 *
 * @param authorName 已經遮蔽過的顯示名稱。遮蔽發生在<b>寫入時</b>，
 *                   完整姓名根本不在資料庫裡（ADR-0014 決策 6）
 * @param edited     這則被改過。畫面要標出來——讀者有權知道它不是原始版本
 */
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
     *            「修改窗口最後一秒還能不能改」因此可以寫成一個固定的測試
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
