package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.application.port.in.PlaceOrderUseCase;
import com.flashsale.domain.shipping.ShippingMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 結帳試算請求體。 */
public record PreviewOrderRequest(

        @NotEmpty(message = "試算至少要有一個品項")
        @Size(max = 50, message = "單筆訂單最多 50 個品項")
        @Valid
        List<PlaceOrderRequest.Item> items,

        /** 要試算的優惠券；不用券時省略。 */
        Long couponId,

        /** 收貨郵遞區號，用來推導運費區域。省略代表還沒選地址—— 此時運費算不出來（回 0 且 {@code shippingKnown} 為 false）。 */
        @Size(max = 8, message = "郵遞區號長度不正確")
        String postalCode,

        /** 配送方式；省略為宅配。 */
        ShippingMethod shippingMethod
) {

    public PlaceOrderUseCase.PreviewCommand toCommand(Long userId) {
        return new PlaceOrderUseCase.PreviewCommand(userId,
                items.stream()
                        .map(item -> new PlaceOrderUseCase.OrderItem(item.skuId(), item.quantity()))
                        .toList(),
                couponId, postalCode, shippingMethod);
    }
}
