package com.flashsale.application.port.in;

/**
 * 訂單的內部註記。
 *
 * <p>與買家備註分開的原因很直接：共用一欄的話，客服寫的「疑似黃牛」
 * 會出現在買家的訂單頁上。這個介面的任何回傳值都不該出現在買家端點上。
 */
public interface OrderStaffNoteUseCase {

    String read(String orderNo);

    void write(String orderNo, String note);
}
