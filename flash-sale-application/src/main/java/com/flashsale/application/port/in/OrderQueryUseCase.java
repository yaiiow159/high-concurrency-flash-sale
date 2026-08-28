package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.OrderView;

/** 訂單查詢入站埠，供前端在拿到受理憑證後輪詢最終結果。 */
public interface OrderQueryUseCase {

    /**
     * 依訂單編號查詢。
     *
     * <p>查不到資料庫紀錄時，會再確認該請求是否仍在非同步處理中，
     * 是則回傳 {@code processing} 狀態而非拋出「訂單不存在」。
     */
    OrderView findByOrderNo(String orderNo, Long userId);
}
