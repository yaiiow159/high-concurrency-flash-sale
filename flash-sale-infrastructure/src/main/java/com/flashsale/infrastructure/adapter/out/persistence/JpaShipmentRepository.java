package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.ShipmentRepository;
import com.flashsale.domain.fulfillment.Carrier;
import com.flashsale.domain.fulfillment.Shipment;
import com.flashsale.domain.fulfillment.ShipmentNo;
import com.flashsale.domain.fulfillment.ShipmentStatus;
import com.flashsale.infrastructure.adapter.out.persistence.entity.ShipmentEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.ShipmentJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 出貨單持久化埠的 JPA 實作。 */
@Repository
public class JpaShipmentRepository implements ShipmentRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaShipmentRepository.class);

    private final ShipmentJpaRepository jpaRepository;

    public JpaShipmentRepository(ShipmentJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    /**
     * 建立出貨單，已存在則不重複建立。
     *
     * <p>先查後寫，再用唯一索引兜底。這是三層冪等的同一個手法：
     * 查詢處理常見情況，唯一索引處理「兩個節點同時通過查詢」的競態——
     * 那是唯一不受競態影響的裁決者。
     */
    @Override
    @Transactional
    public Optional<Shipment> saveIfAbsent(Shipment shipment) {
        if (jpaRepository.existsByOrderNo(shipment.orderNo())) {
            return Optional.empty();
        }
        try {
            ShipmentEntity saved = jpaRepository.saveAndFlush(new ShipmentEntity(
                    shipment.shipmentNo().value(), shipment.orderNo(),
                    shipment.userId(), shipment.status().name(), shipment.createdAt()));
            return Optional.of(toDomain(saved));
        } catch (DataIntegrityViolationException e) {
            // 另一個節點在查詢與寫入之間搶先建立了。這不是錯誤，
            // 正是唯一索引該發揮作用的時刻——Outbox 的至少一次語意保證這會發生。
            log.debug("訂單 {} 的出貨單已由其他節點建立", shipment.orderNo());
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public Shipment update(Shipment shipment) {
        ShipmentEntity entity = jpaRepository.findByShipmentNo(shipment.shipmentNo().value())
                .orElseThrow(() -> new IllegalStateException(
                        "更新時找不到出貨單 " + shipment.shipmentNo().value()));

        entity.applyChanges(
                shipment.carrier() == null ? null : shipment.carrier().name(),
                shipment.trackingNumber(),
                shipment.status().name(),
                shipment.failureReason(),
                shipment.dispatchCount(),
                shipment.shippedAt(),
                shipment.deliveredAt());
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Shipment> findByShipmentNo(ShipmentNo shipmentNo) {
        return jpaRepository.findByShipmentNo(shipmentNo.value())
                .map(JpaShipmentRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Shipment> findByOrderNo(String orderNo) {
        return jpaRepository.findByOrderNo(orderNo).map(JpaShipmentRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Shipment> findByStatus(ShipmentStatus status, int limit) {
        return jpaRepository
                .findByStatusOrderByCreatedAtAsc(status.name(), PageRequest.of(0, limit))
                .stream()
                .map(JpaShipmentRepository::toDomain)
                .toList();
    }

    private static Shipment toDomain(ShipmentEntity entity) {
        return Shipment.restore(
                entity.getId(),
                ShipmentNo.of(entity.getShipmentNo()),
                entity.getOrderNo(),
                entity.getUserId(),
                entity.getCarrier() == null ? null : Carrier.valueOf(entity.getCarrier()),
                entity.getTrackingNumber(),
                ShipmentStatus.valueOf(entity.getStatus()),
                entity.getFailureReason(),
                entity.getDispatchCount(),
                entity.getCreatedAt(),
                entity.getShippedAt(),
                entity.getDeliveredAt());
    }
}
