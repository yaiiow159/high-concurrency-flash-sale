package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.AddressView;

import java.util.List;

/** 收貨地址簿。 */
public interface AddressUseCase {

    List<AddressView> list(Long userId);

    AddressView add(Long userId, AddressCommand command);

    AddressView update(Long userId, Long addressId, AddressCommand command);

    void delete(Long userId, Long addressId);

    /** 設為預設；同時清掉其他地址的預設旗標。 */
    AddressView setDefault(Long userId, Long addressId);

    /** 建立與修改共用的欄位。 */
    record AddressCommand(
            String recipientName,
            String phone,
            String postalCode,
            String region,
            String district,
            String streetAddress,
            boolean defaultAddress) {
    }
}
