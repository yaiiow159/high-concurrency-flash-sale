package com.flashsale.infrastructure.id;

import com.flashsale.application.port.out.PaymentNoGenerator;
import com.flashsale.domain.payment.PaymentNo;
import org.springframework.stereotype.Component;

/**
 * 付款單號的 Snowflake 配接器。
 *
 * <p>與訂單號共用同一個 {@link SnowflakeIdGenerator} 實例。
 * 兩者若各自持有實例，序號會從相同起點開始，同一毫秒內可能產生相同數值——
 * 雖然因為 {@code PAY-} 前綴不同而不會真的衝突，
 * 但那是靠命名空間僥倖，不是靠設計。
 */
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
