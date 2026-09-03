package com.flashsale.infrastructure.id;

import com.flashsale.application.port.out.ReturnNoGenerator;
import com.flashsale.domain.aftersales.ReturnNo;
import org.springframework.stereotype.Component;

/**
 * Snowflake 退貨單號。
 *
 * <p>與訂單號共用同一個產生器實例，因此不同型別的單號永遠不會撞號——
 * 各自維護一套序列才是真正危險的做法。
 */
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
