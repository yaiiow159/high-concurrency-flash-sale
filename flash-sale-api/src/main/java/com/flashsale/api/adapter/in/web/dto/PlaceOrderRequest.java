package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.application.port.in.PlaceOrderUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 一般下單請求體。
 *
 * <p>與 Command 分開的理由同 {@link SeckillRequest}：這是對外契約，
 * 改動成本高；Command 是內部模型，應該能自由重構。
 *
 * <p><b>沒有價格欄位，這是刻意的。</b>單價一律由目錄決定——
 * 呼叫端若能指定價格，那就不叫價格了。同理沒有 {@code userId}：
 * 身分來自令牌，不來自請求內容。
 */
public record PlaceOrderRequest(

        @NotEmpty(message = "訂單至少要有一個品項")
        @Size(max = 50, message = "單筆訂單最多 50 個品項")
        @Valid
        List<Item> items,

        /**
         * 由前端在使用者按下按鈕前產生的冪等鍵（建議用 UUID）。
         * 網路逾時後重送相同的值，會拿回同一張訂單而不是下第二單。
         */
        @NotBlank(message = "requestId 不可為空")
        @Size(max = 64, message = "requestId 長度不可超過 64")
        String requestId,

        /**
         * 收貨地址簿的 ID。訂單存的是它的<b>快照</b>而非這個 ID——
         * 使用者日後搬家改了地址簿，這張訂單要寄到哪裡不能跟著變。
         */
        @NotNull(message = "請選擇收貨地址")
        Long addressId,

        /**
         * 要使用的優惠券；不用券時省略。
         *
         * <p><b>只傳 ID，不傳折抵金額</b>——與價格同一個道理，
         * 呼叫端若能指定折多少，那就不叫折扣了。
         * 滿減這類不需券的優惠由伺服器自行判定，不必也不該由呼叫端指定。
         */
        Long couponId
) {

    public PlaceOrderUseCase.PlaceOrderCommand toCommand(Long userId) {
        return new PlaceOrderUseCase.PlaceOrderCommand(userId, requestId, addressId,
                items.stream()
                        .map(item -> new PlaceOrderUseCase.OrderItem(item.skuId(), item.quantity()))
                        .toList(),
                couponId);
    }

    /** 買哪個規格、幾件。 */
    public record Item(

            @NotNull(message = "skuId 不可為空")
            Long skuId,

            @Min(value = 1, message = "購買數量至少為 1")
            @Max(value = 999, message = "單一品項數量過大")
            int quantity
    ) {
    }
}
