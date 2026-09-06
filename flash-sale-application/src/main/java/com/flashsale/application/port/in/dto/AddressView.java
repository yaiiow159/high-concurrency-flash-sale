package com.flashsale.application.port.in.dto;

import com.flashsale.domain.identity.Address;

/** 收貨地址的對外表述。 */
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
