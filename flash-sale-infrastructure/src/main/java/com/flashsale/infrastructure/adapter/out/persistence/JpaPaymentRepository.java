package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.PaymentRepository;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.payment.Payment;
import com.flashsale.domain.payment.PaymentNo;
import com.flashsale.domain.payment.PaymentStatus;
import com.flashsale.infrastructure.adapter.out.persistence.entity.PaymentEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.PaymentJpaRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 付款持久化埠的 JPA 實作。 */
@Repository
public class JpaPaymentRepository implements PaymentRepository {

    private final PaymentJpaRepository jpaRepository;

    public JpaPaymentRepository(PaymentJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public Payment save(Payment payment) {
        PaymentEntity entity = payment.id() == null
                ? new PaymentEntity(payment.paymentNo().value(), payment.orderNo().value(),
                        payment.userId(), payment.amount(), payment.status().name(),
                        payment.gatewayTransactionId(), payment.createdAt(),
                        payment.paidAt(), payment.failureReason())
                : loadAndApply(payment);
        return toDomain(jpaRepository.save(entity));
    }

    private PaymentEntity loadAndApply(Payment payment) {
        PaymentEntity entity = jpaRepository.findById(payment.id())
                .orElseThrow(() -> new IllegalStateException(
                        "更新付款單時找不到紀錄 id=" + payment.id()));
        entity.applyStateChange(payment.status().name(), payment.gatewayTransactionId(),
                payment.paidAt(), payment.failureReason(), payment.refundedAmount());
        return entity;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> findByPaymentNo(PaymentNo paymentNo) {
        return jpaRepository.findByPaymentNo(paymentNo.value()).map(JpaPaymentRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> findByOrderNo(OrderNo orderNo) {
        return jpaRepository.findByOrderNo(orderNo.value()).map(JpaPaymentRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Payment> findPendingRefunds(int limit) {
        return jpaRepository.findPendingRefunds(Limit.of(limit)).stream()
                .map(JpaPaymentRepository::toDomain)
                .toList();
    }

    private static Payment toDomain(PaymentEntity entity) {
        return Payment.restore(
                entity.getId(),
                PaymentNo.of(entity.getPaymentNo()),
                OrderNo.of(entity.getOrderNo()),
                entity.getUserId(),
                entity.getAmount(),
                PaymentStatus.valueOf(entity.getStatus()),
                entity.getGatewayTransactionId(),
                entity.getCreatedAt(),
                entity.getPaidAt(),
                entity.getFailureReason(),
                entity.getRefundedAmount(),
                entity.getVersion());
    }
}
