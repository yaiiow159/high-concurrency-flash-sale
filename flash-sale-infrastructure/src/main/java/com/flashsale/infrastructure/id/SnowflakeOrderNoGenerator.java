package com.flashsale.infrastructure.id;

import com.flashsale.application.port.out.OrderNoGenerator;
import com.flashsale.domain.order.OrderNo;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * 訂單號的 Snowflake 配接器。
 *
 * <p>只負責把通用的識別碼包裝成 {@link OrderNo}；演算法本身在
 * {@link SnowflakeIdGenerator}，與付款單號共用同一個實例。
 */
@Component
public class SnowflakeOrderNoGenerator implements OrderNoGenerator {

    private final SnowflakeIdGenerator idGenerator;

    public SnowflakeOrderNoGenerator(SnowflakeIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public OrderNo next() {
        return OrderNo.of(Long.toString(idGenerator.nextId()));
    }

    @Override
    public Optional<Instant> issuedAt(OrderNo orderNo) {
        return idGenerator.timestampOf(orderNo.value());
    }
}
