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

    /**
     * 依訂單查付款單。
     *
     * <p>一張訂單至多一張付款單（由 {@code order_no} 唯一索引保證）。
     * 失敗後的重試沿用同一張單而非新建，理由是「這張訂單付了幾次款」
     * 應該是一個明確的事實，而不需要靠掃描多筆紀錄推斷。
     */
    Optional<Payment> findByOrderNo(OrderNo orderNo);

    /**
     * 撈出待退款的付款單。
     *
     * <p>每一筆都代表有一筆錢卡在系統裡，必須被監控與排程看到。
     */
    List<Payment> findPendingRefunds(int limit);
}
