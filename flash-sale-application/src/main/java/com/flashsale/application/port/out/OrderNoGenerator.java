package com.flashsale.application.port.out;

import com.flashsale.domain.order.OrderNo;

import java.time.Instant;
import java.util.Optional;

/** 訂單編號產生器埠（出站）。 */
public interface OrderNoGenerator {

    OrderNo next();

    /** 解出訂單號內嵌的產生時間。 */
    Optional<Instant> issuedAt(OrderNo orderNo);
}
