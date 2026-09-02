package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.AddressView;

import java.util.List;

/**
 * 收貨地址簿。
 *
 * <p>每個方法都帶 {@code userId}，而且是<b>第一個參數</b>——
 * 這讓「忘了檢查擁有者」變成一個編譯期就看得出來的疏忽，
 * 而不是要靠 review 才發現的洞。地址是個資，
 * 少一道擁有者檢查等於任何人都能讀到別人的住家地址。
 */
public interface AddressUseCase {

    List<AddressView> list(Long userId);

    AddressView add(Long userId, AddressCommand command);

    AddressView update(Long userId, Long addressId, AddressCommand command);

    void delete(Long userId, Long addressId);

    /** 設為預設；同時清掉其他地址的預設旗標。 */
    AddressView setDefault(Long userId, Long addressId);

    /**
     * 建立與修改共用的欄位。
     *
     * <p>不含 {@code userId}——身分一律來自令牌，不來自請求內容。
     * 讓呼叫端自己填 userId，等於讓任何人都能往別人的地址簿裡塞資料。
     */
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
