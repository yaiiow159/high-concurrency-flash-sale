package com.flashsale.application.port.out;

import com.flashsale.domain.fulfillment.Shipment;
import com.flashsale.domain.fulfillment.ShipmentNo;
import com.flashsale.domain.fulfillment.ShipmentStatus;

import java.util.List;
import java.util.Optional;

/** 出貨單持久化埠（出站）。 */
public interface ShipmentRepository {

    /**
     * 建立出貨單；同一張訂單已有出貨單時不重複建立。
     *
     * <p><b>回傳 Optional 而非拋例外</b>：這個方法會被 MQ 消費端呼叫，
     * 而 Outbox 是至少一次語意——重複投遞是常態不是異常。
     * 與 {@code OrderRepository.saveIfAbsent} 同一個手法。
     *
     * @return 本次真的建立時回傳出貨單；已存在則回傳 {@code Optional.empty()}
     */
    Optional<Shipment> saveIfAbsent(Shipment shipment);

    Shipment update(Shipment shipment);

    Optional<Shipment> findByShipmentNo(ShipmentNo shipmentNo);

    Optional<Shipment> findByOrderNo(String orderNo);

    /** 待出貨清單，供營運後台揀貨。 */
    List<Shipment> findByStatus(ShipmentStatus status, int limit);
}
