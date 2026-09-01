package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 付款單的持久化模型。
 *
 * <p><b>{@code order_no} 的唯一約束是防重複收款的結構性保證。</b>
 * 一張訂單至多一張付款單；失敗後的重試沿用同一張單而非新建，
 * 讓「這張訂單收了幾次錢」成為一個明確的事實，
 * 而不需要靠掃描多筆紀錄推斷。
 */
@Entity
@Table(name = "payment",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_no", columnNames = "payment_no"),
                @UniqueConstraint(name = "uk_payment_order_no", columnNames = "order_no")
        },
        indexes = {
                // 待退款掃描：WHERE status = 'REFUND_PENDING'
                @Index(name = "idx_payment_status", columnList = "status"),
                @Index(name = "idx_payment_user", columnList = "user_id")
        })
public class PaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_no", nullable = false, length = 64, updatable = false)
    private String paymentNo;

    @Column(name = "order_no", nullable = false, length = 64, updatable = false)
    private String orderNo;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** 建立時從訂單複製，之後不可變——否則「應付」與「實付」會失去對應關係。 */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    /** 閘道交易編號，對帳時的唯一憑據。 */
    @Column(name = "gateway_transaction_id", length = 64)
    private String gatewayTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "failure_reason", length = 256)
    private String failureReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PaymentEntity() {
        // JPA 專用
    }

    public PaymentEntity(String paymentNo, String orderNo, Long userId, BigDecimal amount,
                         String status, String gatewayTransactionId, Instant createdAt,
                         Instant paidAt, String failureReason) {
        this.paymentNo = paymentNo;
        this.orderNo = orderNo;
        this.userId = userId;
        this.amount = amount;
        this.status = status;
        this.gatewayTransactionId = gatewayTransactionId;
        this.createdAt = createdAt;
        this.paidAt = paidAt;
        this.failureReason = failureReason;
    }

    public void applyStateChange(String status, String gatewayTransactionId,
                                 Instant paidAt, String failureReason) {
        this.status = status;
        this.gatewayTransactionId = gatewayTransactionId;
        this.paidAt = paidAt;
        this.failureReason = failureReason;
    }

    public Long getId() {
        return id;
    }

    public String getPaymentNo() {
        return paymentNo;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getStatus() {
        return status;
    }

    public String getGatewayTransactionId() {
        return gatewayTransactionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public long getVersion() {
        return version;
    }
}
