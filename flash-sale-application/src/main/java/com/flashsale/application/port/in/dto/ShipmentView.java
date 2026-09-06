package com.flashsale.application.port.in.dto;

import com.flashsale.domain.fulfillment.Shipment;

import java.time.Instant;

/** 出貨單的對外表述。 */
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
