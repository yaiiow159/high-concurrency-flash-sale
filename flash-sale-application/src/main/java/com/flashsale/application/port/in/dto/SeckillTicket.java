package com.flashsale.application.port.in.dto;

/**
 * 搶購受理憑證。
 *
 * <p>搶購 API 是<b>非同步</b>的：庫存扣減成功即回傳此憑證（HTTP 202），
 * 真正的落庫由 MQ 消費端完成。前端拿 {@code orderNo} 輪詢
 * {@code GET /api/v1/orders/{orderNo}} 得知最終結果。
 *
 * <p>這是削峰的核心——把「寫資料庫」這個最慢的環節移出請求鏈路。
 */
public record SeckillTicket(String orderNo, String message) {

    public static SeckillTicket accepted(String orderNo) {
        return new SeckillTicket(orderNo, "搶購請求已受理，請稍候查詢訂單結果");
    }
}
