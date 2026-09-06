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

/** 運費費率的 JPA 實作。 */
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
