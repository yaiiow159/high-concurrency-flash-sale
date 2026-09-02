package com.flashsale.infrastructure.id;

import com.flashsale.application.port.out.ShipmentNoGenerator;
import com.flashsale.domain.fulfillment.ShipmentNo;
import org.springframework.stereotype.Component;

/**
 * 出貨單號產生器。
 *
 * <p>與訂單號、付款單號<b>共用同一個 {@link SnowflakeIdGenerator} 實例</b>。
 * 各自 new 一個會讓三者的序號從同一個點開始，
 * 同一毫秒內就可能產生相同的 ID。
 */
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
