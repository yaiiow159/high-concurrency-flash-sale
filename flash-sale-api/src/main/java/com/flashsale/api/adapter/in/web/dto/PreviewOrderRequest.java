package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.application.port.in.PlaceOrderUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 結帳試算請求體。
 *
 * <p><b>沒有 addressId 與 requestId</b>——試算什麼都不建立，
 * 那兩個欄位是為了「建立訂單」存在的（寄不出去的訂單不該被建立、
 * 沒有冪等鍵就沒有冪等）。硬要求它們只會讓使用者還沒選地址就看不到折扣。
 */
public record PreviewOrderRequest(

        @NotEmpty(message = "試算至少要有一個品項")
        @Size(max = 50, message = "單筆訂單最多 50 個品項")
        @Valid
        List<PlaceOrderRequest.Item> items,

        /** 要試算的優惠券；不用券時省略。 */
        Long couponId
) {

    public PlaceOrderUseCase.PreviewCommand toCommand(Long userId) {
        return new PlaceOrderUseCase.PreviewCommand(userId,
                items.stream()
                        .map(item -> new PlaceOrderUseCase.OrderItem(item.skuId(), item.quantity()))
                        .toList(),
                couponId);
    }
}
