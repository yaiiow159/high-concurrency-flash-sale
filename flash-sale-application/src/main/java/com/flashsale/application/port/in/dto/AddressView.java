package com.flashsale.application.port.in.dto;

import com.flashsale.domain.identity.Address;

/**
 * 收貨地址的對外表述。
 *
 * <p>電話<b>不遮蔽</b>：這是使用者自己的地址簿，遮了他就沒辦法確認自己填對沒有。
 * 遮蔽用在日誌與客服介面——那裡看的人不是資料的主人。
 */
public record AddressView(
        Long addressId,
        String recipientName,
        String phone,
        String postalCode,
        String region,
        String district,
        String streetAddress,
        String fullAddress,
        boolean defaultAddress
) {

    public static AddressView from(Address address) {
        return new AddressView(
                address.id(),
                address.recipientName(),
                address.phone(),
                address.postalCode(),
                address.region(),
                address.district(),
                address.streetAddress(),
                address.fullAddress(),
                address.isDefaultAddress());
    }
}
