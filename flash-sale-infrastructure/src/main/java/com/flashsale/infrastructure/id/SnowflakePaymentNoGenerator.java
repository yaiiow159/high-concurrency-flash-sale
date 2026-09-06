package com.flashsale.infrastructure.id;

import com.flashsale.application.port.out.PaymentNoGenerator;
import com.flashsale.domain.payment.PaymentNo;
import org.springframework.stereotype.Component;

/** 付款單號的 Snowflake 配接器。 */
@Component
public class SnowflakePaymentNoGenerator implements PaymentNoGenerator {

    private final SnowflakeIdGenerator idGenerator;

    public SnowflakePaymentNoGenerator(SnowflakeIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public PaymentNo next() {
        return PaymentNo.fromId(idGenerator.nextId());
    }
}
