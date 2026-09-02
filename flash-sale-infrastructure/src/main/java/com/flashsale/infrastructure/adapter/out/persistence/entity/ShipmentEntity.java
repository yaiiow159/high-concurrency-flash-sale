package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 出貨單的持久化模型。
 *
 * <p><b>沒有收貨地址欄位。</b>地址快照在訂單上，出貨單再存一份就會出現
 * 「訂單寫台北、出貨單寫高雄」這種沒有人能仲裁的狀態。
 *
 * <p>{@code order_no} 目前是唯一索引（一張訂單一張出貨單）。
 * 未來要支援分批出貨時，把它降級成一般索引即可——
 * 領域模型那邊也刻意沒有假設一對一。
 */
@Entity
@Table(name = "shipment",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_shipment_no", columnNames = "shipment_no"),
                @UniqueConstraint(name = "uk_shipment_order", columnNames = "order_no")
        },
        indexes = {
                @Index(name = "idx_shipment_status", columnList = "status, created_at"),
                @Index(name = "idx_shipment_user", columnList = "user_id")
        })
public class ShipmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shipment_no", nullable = false, length = 64, updatable = false)
    private String shipmentNo;

    @Column(name = "order_no", nullable = false, length = 64, updatable = false)
    private String orderNo;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "carrier", length = 16)
    private String carrier;

    @Column(name = "tracking_number", length = 64)
    private String trackingNumber;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "failure_reason", length = 256)
    private String failureReason;

    /** 派送次數。大於 1 代表曾配送失敗後重送，是物流品質的指標。 */
    @Column(name = "dispatch_count", nullable = false)
    private int dispatchCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 第一次出貨的時間。重新派送不覆寫——它是出貨時效的分母。 */
    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected ShipmentEntity() {
    }

    public ShipmentEntity(String shipmentNo, String orderNo, Long userId, String status,
                          Instant createdAt) {
        this.shipmentNo = shipmentNo;
        this.orderNo = orderNo;
        this.userId = userId;
        this.status = status;
        this.dispatchCount = 0;
        this.createdAt = createdAt;
    }

    public void applyChanges(String carrier, String trackingNumber, String status,
                             String failureReason, int dispatchCount,
                             Instant shippedAt, Instant deliveredAt) {
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.status = status;
        this.failureReason = failureReason;
        this.dispatchCount = dispatchCount;
        this.shippedAt = shippedAt;
        this.deliveredAt = deliveredAt;
    }

    public Long getId() {
        return id;
    }

    public String getShipmentNo() {
        return shipmentNo;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public String getCarrier() {
        return carrier;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public String getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getDispatchCount() {
        return dispatchCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }
}
