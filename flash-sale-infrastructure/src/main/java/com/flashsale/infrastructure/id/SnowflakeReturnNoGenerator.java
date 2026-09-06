package com.flashsale.infrastructure.id;

import com.flashsale.application.port.out.ReturnNoGenerator;
import com.flashsale.domain.aftersales.ReturnNo;
import org.springframework.stereotype.Component;

/** Snowflake 退貨單號。 */
@Component
public class SnowflakeReturnNoGenerator implements ReturnNoGenerator {

    private final SnowflakeIdGenerator idGenerator;

    public SnowflakeReturnNoGenerator(SnowflakeIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public ReturnNo next() {
        return ReturnNo.of(ReturnNo.PREFIX + idGenerator.nextId());
    }
}
