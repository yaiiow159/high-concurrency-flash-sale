package com.flashsale.application.port.out;

import com.flashsale.domain.identity.Address;

import java.util.List;
import java.util.Optional;

/** 收貨地址持久化埠（出站）。 */
public interface AddressRepository {

    Address save(Address address);

    Optional<Address> findById(Long addressId);

    /** 某使用者的地址簿，預設地址排最前。 */
    List<Address> findByUserId(Long userId);

    int countByUserId(Long userId);

    void deleteById(Long addressId);

    /** 把該使用者其餘地址的預設旗標清掉。 */
    int clearDefaultExcept(Long userId, Long exceptAddressId);
}
