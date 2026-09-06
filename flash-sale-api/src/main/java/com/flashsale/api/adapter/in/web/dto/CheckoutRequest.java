package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.domain.shipping.ShippingMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 購物車結帳請求體。 */
public record CheckoutRequest(

        @NotBlank(message = "requestId 不可為空")
        @Size(max = 64, message = "requestId 長度不可超過 64")
        String requestId,

        @NotNull(message = "請選擇收貨地址")
        Long addressId,

        /** 要使用的優惠券；不用券時省略。只傳 ID，折抵金額由伺服器算。 */
        Long couponId,

        /** 配送方式；省略為宅配。 */
        ShippingMethod shippingMethod
) {
}
