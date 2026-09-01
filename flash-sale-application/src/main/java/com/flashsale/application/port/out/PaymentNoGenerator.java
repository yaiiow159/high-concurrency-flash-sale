package com.flashsale.application.port.out;

import com.flashsale.domain.payment.PaymentNo;

/** 付款單號產生器埠（出站）。 */
public interface PaymentNoGenerator {

    PaymentNo next();
}
