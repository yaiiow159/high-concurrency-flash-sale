package com.flashsale.infrastructure.id;

import com.flashsale.application.port.out.ShipmentNoGenerator;
import com.flashsale.domain.fulfillment.ShipmentNo;
import org.springframework.stereotype.Component;

/** 出貨單號產生器。 */
@Component
public class SnowflakeShipmentNoGenerator implements ShipmentNoGenerator {

    private final SnowflakeIdGenerator idGenerator;

    public SnowflakeShipmentNoGenerator(SnowflakeIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public ShipmentNo next() {
        return ShipmentNo.of(String.valueOf(idGenerator.nextId()));
    }
}
