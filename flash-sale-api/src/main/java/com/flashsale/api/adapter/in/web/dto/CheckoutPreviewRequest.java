package com.flashsale.api.adapter.in.web.dto;

/**
 * 購物車結帳試算請求體。
 *
 * <p>沒有品項清單，與 {@link CheckoutRequest} 同一個理由：
 * 買什麼由伺服器從購物車讀。試算若讓前端送品項，
 * 就會出現「試算的內容與真正下單的內容不同」，而那是最難查的一種不一致。
 */
public record CheckoutPreviewRequest(Long couponId) {
}
