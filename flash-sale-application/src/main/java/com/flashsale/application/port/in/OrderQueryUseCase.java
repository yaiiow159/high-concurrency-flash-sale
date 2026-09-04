package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.OrderView;

import java.util.List;

/** 訂單查詢入站埠，供前端在拿到受理憑證後輪詢最終結果。 */
public interface OrderQueryUseCase {

    /**
     * 依訂單編號查詢。
     *
     * <p>查不到資料庫紀錄時，會再確認該請求是否仍在非同步處理中，
     * 是則回傳 {@code processing} 狀態而非拋出「訂單不存在」。
     */
    OrderView findByOrderNo(String orderNo, Long userId);

    /**
     * 某使用者的訂單列表，新到舊。
     *
     * <p>頁數上限由 Use Case 夾住：這是登入後就能無限次呼叫的端點，
     * 沒有上限的話任何人都能用 {@code size=1000000} 讓資料庫掃全表。
     */
    /**
     * 我的訂單。
     *
     * @param status 只看某個狀態；{@code null} 或空字串代表全部。
     *               訂單一多就只能一直往下捲，而使用者要找的通常是
     *               「待付款」或「待收貨」那幾筆
     */
    List<OrderView> listForUser(Long userId, String status, int page, int size);
}
