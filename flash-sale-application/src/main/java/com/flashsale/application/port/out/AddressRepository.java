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

    /**
     * 把該使用者其餘地址的預設旗標清掉。
     *
     * <p><b>「每人只有一筆預設地址」這條不變式跨越多個聚合，
     * 資料庫也表達不了</b>（MySQL 沒有部分唯一索引，
     * 而 {@code UNIQUE(user_id, is_default)} 會連「多筆非預設」都一起擋掉）。
     * 因此它只能靠應用層在同一個交易裡維持——這是刻意接受的取捨，
     * 代價是若有人繞過這個方法直接寫資料，不變式就會破。
     *
     * @param exceptAddressId 要保留為預設的那一筆
     * @return 實際被清掉旗標的筆數
     */
    int clearDefaultExcept(Long userId, Long exceptAddressId);
}
