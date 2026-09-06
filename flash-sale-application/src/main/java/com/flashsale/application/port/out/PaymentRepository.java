package com.flashsale.application.port.out;

import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.payment.Payment;
import com.flashsale.domain.payment.PaymentNo;

import java.util.List;
import java.util.Optional;

/** 付款持久化埠（出站）。 */
public interface PaymentRepository {

    Payment save(Payment payment);

    Optional<Payment> findByPaymentNo(PaymentNo paymentNo);

    /** 依訂單查付款單。 */
    Optional<Payment> findByOrderNo(OrderNo orderNo);

    /** 撈出待退款的付款單。 */
    List<Payment> findPendingRefunds(int limit);
}
