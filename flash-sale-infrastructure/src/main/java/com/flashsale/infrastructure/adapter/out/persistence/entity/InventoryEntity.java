package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * SKU 庫存的持久化模型。
 *
 * <p>主鍵直接用 {@code sku_id} 而非另開一個自增 id：庫存與 SKU 是一對一，
 * 多一個代理鍵只會多一次 join，且讓「一個 SKU 兩筆庫存」變成資料庫允許的狀態。
 *
 * <p>{@code version} 是樂觀鎖的依據。一般 SKU 衝突率極低（多數一天只賣個位數），
 * 樂觀鎖的重試成本可以忽略——這正是 ADR-0008 判斷 DB 對這條通道「完全夠用」的理由。
 * 秒殺不能用這套，因為所有請求競爭同一行，樂觀鎖會退化成活鎖。
 */
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
