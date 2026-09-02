package com.flashsale.application.port.in.dto;

import com.flashsale.domain.fulfillment.Shipment;

import java.time.Instant;

/**
 * 出貨單的對外表述。
 *
 * <p>{@code trackingUrl} 由承運商列舉算出而非存在資料庫——
 * 查詢網址是承運商的屬性，不是這張出貨單的屬性。
 * 存下來的話，物流商改網址時所有歷史出貨單都會連到一個 404。
 */
public record ShipmentView(
        String shipmentNo,
        String orderNo,
        String carrier,
        String carrierName,
        String trackingNumber,
        String trackingUrl,
        String status,
        String failureReason,
        int dispatchCount,
        Instant shippedAt,
        Instant deliveredAt
) {

    public static ShipmentView from(Shipment shipment) {
        return new ShipmentView(
                shipment.shipmentNo().value(),
                shipment.orderNo(),
                shipment.carrier() == null ? null : shipment.carrier().name(),
                shipment.carrier() == null ? null : shipment.carrier().displayName(),
                shipment.trackingNumber(),
                shipment.carrier() == null || shipment.trackingNumber() == null
                        ? null
                        : shipment.carrier().trackingUrl(shipment.trackingNumber()),
                shipment.status().name(),
                shipment.failureReason(),
                shipment.dispatchCount(),
                shipment.shippedAt(),
                shipment.deliveredAt());
    }
}
