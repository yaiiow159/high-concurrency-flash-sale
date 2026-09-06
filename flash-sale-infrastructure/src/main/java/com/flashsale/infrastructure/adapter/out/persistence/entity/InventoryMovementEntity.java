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

/** 庫存異動流水的持久化模型。 */
@Entity
@Table(name = "inventory_movement",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_movement_ref",
                columnNames = {"ref_type", "ref_no", "type", "sku_id"}),
        indexes = {
                @Index(name = "idx_movement_sku", columnList = "sku_id, created_at"),
                @Index(name = "idx_movement_created", columnList = "created_at")
        })
public class InventoryMovementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "type", nullable = false, length = 16)
    private String type;

    /** 對可售量的增減。負數代表減少。 */
    @Column(name = "available_delta", nullable = false)
    private int availableDelta;

    /** 對劃撥量的增減。負數代表減少。 */
    @Column(name = "allocated_delta", nullable = false)
    private int allocatedDelta;

    @Column(name = "ref_type", nullable = false, length = 16)
    private String refType;

    @Column(name = "ref_no", nullable = false, length = 64)
    private String refNo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InventoryMovementEntity() {
    }

    public InventoryMovementEntity(Long skuId, String type, int availableDelta,
                                   int allocatedDelta, String refType, String refNo,
                                   Instant createdAt) {
        this.skuId = skuId;
        this.type = type;
        this.availableDelta = availableDelta;
        this.allocatedDelta = allocatedDelta;
        this.refType = refType;
        this.refNo = refNo;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getSkuId() {
        return skuId;
    }

    public String getType() {
        return type;
    }

    public int getAvailableDelta() {
        return availableDelta;
    }

    public int getAllocatedDelta() {
        return allocatedDelta;
    }

    public String getRefType() {
        return refType;
    }

    public String getRefNo() {
        return refNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
