package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.AddressEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AddressJpaRepository extends JpaRepository<AddressEntity, Long> {

    /** 預設地址排最前，其餘依建立時間新到舊——結帳時第一筆就是使用者最可能要的。 */
    List<AddressEntity> findByUserIdOrderByDefaultAddressDescCreatedAtDesc(Long userId);

    int countByUserId(Long userId);

    /**
     * 清掉該使用者其餘地址的預設旗標。
     *
     * <p>用單一 UPDATE 而非「撈出來逐筆改」：後者在地址數量多時是 N 次寫入，
     * 而且中間任何一筆失敗都會留下兩筆預設地址的中間態。
     */
    @Modifying
    @Query("""
            update AddressEntity a set a.defaultAddress = false
             where a.userId = :userId and a.id <> :exceptId and a.defaultAddress = true
            """)
    int clearDefaultExcept(@Param("userId") Long userId, @Param("exceptId") Long exceptId);
}
