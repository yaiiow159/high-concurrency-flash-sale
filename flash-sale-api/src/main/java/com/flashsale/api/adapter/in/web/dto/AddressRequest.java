package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.application.port.in.AddressUseCase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 收貨地址請求體。
 *
 * <p>沒有 {@code userId}——身分來自令牌，不來自請求內容。
 * 讓呼叫端自己填，等於任何人都能往別人的地址簿裡塞資料。
 */
public record AddressRequest(

        @NotBlank(message = "收件人不可為空")
        @Size(max = 32, message = "收件人不可超過 32 字")
        String recipientName,

        @NotBlank(message = "聯絡電話不可為空")
        @Pattern(regexp = "^[0-9+() -]{8,24}$", message = "聯絡電話格式不正確")
        String phone,

        @Pattern(regexp = "^[0-9]{3,6}$", message = "郵遞區號格式不正確")
        String postalCode,

        @NotBlank(message = "縣市不可為空")
        @Size(max = 32)
        String region,

        @NotBlank(message = "鄉鎮市區不可為空")
        @Size(max = 32)
        String district,

        @NotBlank(message = "地址不可為空")
        @Size(max = 128, message = "地址不可超過 128 字")
        String streetAddress,

        boolean defaultAddress
) {

    public AddressUseCase.AddressCommand toCommand() {
        return new AddressUseCase.AddressCommand(recipientName, phone, postalCode,
                region, district, streetAddress, defaultAddress);
    }
}
