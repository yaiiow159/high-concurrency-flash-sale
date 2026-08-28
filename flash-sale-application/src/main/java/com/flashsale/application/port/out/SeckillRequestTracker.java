package com.flashsale.application.port.out;

import java.util.Optional;

/**
 * 搶購請求狀態追蹤埠（出站）。
 *
 * <p>解決非同步下單的一個實務問題：庫存扣了、訊息投了、訂單號也回給前端了，
 * 但消費端還沒把訂單寫進 DB。此時前端來查訂單，資料庫回「不存在」，
 * 使用者會以為搶購失敗——但庫存其實已經是他的了。
 *
 * <p>這個埠記錄「此訂單號已被受理」，讓查詢端能回覆 {@code PROCESSING} 而非 404。
 */
public interface SeckillRequestTracker {

    /** 標記訂單號已受理（庫存已扣、訊息已投遞）。 */
    void markAccepted(String orderNo, Long userId);

    /** 標記處理失敗，讓前端能立刻停止輪詢並顯示原因。 */
    void markFailed(String orderNo, String reason);

    /** 查詢受理狀態；回傳 {@code Optional.empty()} 代表從未受理過此訂單號。 */
    Optional<RequestStatus> find(String orderNo);

    /** 受理狀態快照。 */
    record RequestStatus(String orderNo, Long userId, boolean failed, String reason) {

        public static RequestStatus accepted(String orderNo, Long userId) {
            return new RequestStatus(orderNo, userId, false, null);
        }

        public static RequestStatus failed(String orderNo, String reason) {
            return new RequestStatus(orderNo, null, true, reason);
        }
    }
}
