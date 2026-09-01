package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.PaymentEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/** 付款單的 Spring Data 介面。 */
public interface PaymentJpaRepository extends JpaRepository<PaymentEntity, Long> {

    Optional<PaymentEntity> findByPaymentNo(String paymentNo);

    Optional<PaymentEntity> findByOrderNo(String orderNo);

    /**
     * 撈出待退款的付款單。
     *
     * <p>每一筆都代表有一筆錢收了卻沒有訂單對應，正卡在系統裡。
     * 這個查詢的結果<b>應該永遠是空的</b>——不是空的就代表發生了競態。
     */
    @Query("""
            select p from PaymentEntity p
            where p.status = 'REFUND_PENDING'
            order by p.createdAt asc
            """)
    List<PaymentEntity> findPendingRefunds(Limit limit);
}
