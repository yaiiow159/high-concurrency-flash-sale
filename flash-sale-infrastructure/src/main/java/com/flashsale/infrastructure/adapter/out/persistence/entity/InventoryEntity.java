package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** SKU 庫存的持久化模型。 */
@Entity
@Table(name = "inventory")
public class InventoryEntity {

    @Id
    @Column(name = "sku_id")
    private Long skuId;

    /** 可自由販售的量。 */
    @Column(name = "available", nullable = false)
    private int available;

    /** 已劃撥給秒殺活動、由 Redis 代管的量。 */
    @Column(name = "allocated", nullable = false)
    private int allocated;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InventoryEntity() {
    }

    public InventoryEntity(Long skuId, int available, int allocated, Instant updatedAt) {
        this.skuId = skuId;
        this.available = available;
        this.allocated = allocated;
        this.updatedAt = updatedAt;
    }

    public void applyChanges(int available, int allocated, Instant updatedAt) {
        this.available = available;
        this.allocated = allocated;
        this.updatedAt = updatedAt;
    }

    public Long getSkuId() {
        return skuId;
    }

    public int getAvailable() {
        return available;
    }

    public int getAllocated() {
        return allocated;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
