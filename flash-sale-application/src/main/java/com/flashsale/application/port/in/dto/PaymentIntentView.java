package com.flashsale.application.port.in.dto;

/**
 * 發起付款的結果，回傳給前端。
 *
 * <p>前端拿 {@code paymentUrl} 導向金流頁面，之後靠輪詢訂單狀態得知結果——
 * <b>不可以拿回調當作前端的完成訊號</b>，因為回調是閘道打給伺服器的，
 * 使用者的瀏覽器可能早就關掉了。
 */
public record PaymentIntentView(
        String paymentNo,
        String orderNo,
        String paymentUrl,
        String status
) {
}
