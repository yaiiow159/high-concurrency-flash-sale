package com.flashsale.api.adapter.in.web.dto;

/**
 * 購物車結帳試算請求體。
 *
 * <p>沒有品項清單，與 {@link CheckoutRequest} 同一個理由：
 * 買什麼由伺服器從購物車讀。試算若讓前端送品項，
 * 就會出現「試算的內容與真正下單的內容不同」，而那是最難查的一種不一致。
 */
public record CheckoutPreviewRequest(
        Long couponId,
        /**
         * 用來算運費的收貨地址；省略代表使用者還沒選。
         *
         * <p><b>送地址 ID 而不是郵遞區號</b>：讓呼叫端傳郵遞區號等於讓它
         * 決定運費區域，而離島是本島的兩三倍。
         */
        Long addressId
) {
}
