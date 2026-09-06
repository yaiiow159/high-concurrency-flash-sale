package com.flashsale.api.adapter.in.web.dto;

/** 購物車結帳試算請求體。 */
public record CheckoutPreviewRequest(
        Long couponId,
        /** 用來算運費的收貨地址；省略代表使用者還沒選。 */
        Long addressId
) {
}
