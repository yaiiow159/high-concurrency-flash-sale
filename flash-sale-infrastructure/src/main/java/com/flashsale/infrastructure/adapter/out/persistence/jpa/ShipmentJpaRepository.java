package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.ShipmentEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShipmentJpaRepository extends JpaRepository<ShipmentEntity, Long> {

    Optional<ShipmentEntity> findByShipmentNo(String shipmentNo);

    Optional<ShipmentEntity> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    /** 待處理清單依建立時間由舊到新——先進來的先出貨。 */
    List<ShipmentEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);
}
