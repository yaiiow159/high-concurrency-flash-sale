package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.ShippingRateRepository;
import com.flashsale.domain.shipping.ShippingMethod;
import com.flashsale.domain.shipping.ShippingRate;
import com.flashsale.domain.shipping.ShippingZone;
import com.flashsale.infrastructure.adapter.out.persistence.entity.ShippingRateEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.ShippingRateJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 運費費率的 JPA 實作。
 *
 * <p><b>沒有快取。</b> 費率表只有十幾筆、而且下單本來就是走資料庫的路徑
 * （一般下單全程在一個交易裡），多一次主鍵以外的小查詢可以忽略。
 * 加快取則多一個會過期的東西——而運費算錯的代價是每一單都錯。
 */
@Repository
public class JpaShippingRateRepository implements ShippingRateRepository {

    private final ShippingRateJpaRepository jpaRepository;

    public JpaShippingRateRepository(ShippingRateJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShippingRate> findAll() {
        return jpaRepository.findAll().stream()
                .map(JpaShippingRateRepository::toDomain)
                .toList();
    }

    private static ShippingRate toDomain(ShippingRateEntity entity) {
        return new ShippingRate(
                ShippingMethod.valueOf(entity.getMethod()),
                ShippingZone.valueOf(entity.getZone()),
                entity.getMaxWeightGrams(),
                entity.getFee());
    }
}
