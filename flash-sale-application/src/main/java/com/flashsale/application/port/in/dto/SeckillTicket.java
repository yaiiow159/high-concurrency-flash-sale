package com.flashsale.application.port.in.dto;

/** 搶購受理憑證。 */
public record SeckillTicket(String orderNo, String message) {

    public static SeckillTicket accepted(String orderNo) {
        return new SeckillTicket(orderNo, "搶購請求已受理，請稍候查詢訂單結果");
    }
}
