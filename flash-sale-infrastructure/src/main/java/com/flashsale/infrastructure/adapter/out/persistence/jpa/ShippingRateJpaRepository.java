package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.ShippingRateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShippingRateJpaRepository extends JpaRepository<ShippingRateEntity, Long> {
}
