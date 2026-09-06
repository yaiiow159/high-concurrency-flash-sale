package com.flashsale.application.port.out;

import com.flashsale.domain.fulfillment.Shipment;
import com.flashsale.domain.fulfillment.ShipmentNo;
import com.flashsale.domain.fulfillment.ShipmentStatus;

import java.util.List;
import java.util.Optional;

/** 出貨單持久化埠（出站）。 */
public interface ShipmentRepository {

    /** 建立出貨單；同一張訂單已有出貨單時不重複建立。 */
    Optional<Shipment> saveIfAbsent(Shipment shipment);

    Shipment update(Shipment shipment);

    Optional<Shipment> findByShipmentNo(ShipmentNo shipmentNo);

    Optional<Shipment> findByOrderNo(String orderNo);

    /** 待出貨清單，供營運後台揀貨。 */
    List<Shipment> findByStatus(ShipmentStatus status, int limit);
}
