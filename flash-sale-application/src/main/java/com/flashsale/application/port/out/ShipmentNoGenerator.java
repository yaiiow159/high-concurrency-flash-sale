package com.flashsale.application.port.out;

import com.flashsale.domain.fulfillment.ShipmentNo;

/** 出貨單號產生器。 */
public interface ShipmentNoGenerator {

    ShipmentNo next();
}
