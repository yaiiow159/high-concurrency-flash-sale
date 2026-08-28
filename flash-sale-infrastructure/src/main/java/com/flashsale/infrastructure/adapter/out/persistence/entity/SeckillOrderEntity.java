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
 * 訂單的持久化模型。
 *
 * <p><b>{@code request_id} 的唯一約束是全系統防重複下單的最後一道防線。</b>
 * Redis 冪等、MQ 冪等都可能因為鍵過期或消費組重置而失效，
 * 唯有資料庫的唯一索引是永久且無條件成立的。
 */
@Entity
@Table(
        name = "seckill_order",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_order_no", columnNames = "order_no"),
                @UniqueConstraint(name = "uk_request_id", columnNames = "request_id")
        },
        indexes = {
                // 逾期關單排程的查詢條件：status + created_at
                @Index(name = "idx_status_created", columnList = "status,created_at"),
                @Index(name = "idx_user_activity", columnList = "user_id,activity_id")
        })
public class SeckillOrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "order_no", nullable = false, length = 64, updatable = false)
    private String orderNo;

    @Column(name = "activity_id", nullable = false, updatable = false)
    private Long activityId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "request_id", nullable = false, length = 64, updatable = false)
    private String requestId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "close_reason", length = 128)
    private String closeReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SeckillOrderEntity() {
        // JPA 專用
    }

    public SeckillOrderEntity(String orderNo, Long activityId, Long userId, String requestId,
                              int quantity, BigDecimal amount, String status,
                              Instant createdAt, Instant paidAt, String closeReason, long version) {
        this.orderNo = orderNo;
        this.activityId = activityId;
        this.userId = userId;
        this.requestId = requestId;
        this.quantity = quantity;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
        this.paidAt = paidAt;
        this.closeReason = closeReason;
        this.version = version;
    }

    /** 狀態流轉時的欄位更新；只開放會變動的欄位，其餘由 {@code updatable = false} 鎖死。 */
    public void applyStateChange(String status, Instant paidAt, String closeReason) {
        this.status = status;
        this.paidAt = paidAt;
        this.closeReason = closeReason;
    }

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public Long getActivityId() {
        return activityId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getRequestId() {
        return requestId;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getCloseReason() {
        return closeReason;
    }

    public long getVersion() {
        return version;
    }
}
