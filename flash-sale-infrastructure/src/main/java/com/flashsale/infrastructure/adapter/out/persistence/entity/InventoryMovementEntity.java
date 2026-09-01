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
 * 庫存異動流水的持久化模型。
 *
 * <p><b>{@code (ref_type, ref_no, type, sku_id)} 的唯一索引是冪等的最後一道保險。</b>
 * 應用層會先查再寫，但「查」與「寫」之間有時間差；
 * 兩台機器同時執行同一場活動的釋放時，兩邊都會查到「還沒釋放過」。
 * 唯一索引讓資料庫來裁決，這是唯一不受競態影響的地方。
 *
 * <p>唯一鍵包含 {@code type} 與 {@code sku_id}，兩者都不可省：
 * <ul>
 *   <li>少了 {@code type}：同一筆訂單的 DEDUCT 與後續 RESTORE 會互相排斥</li>
 *   <li>少了 {@code sku_id}：<b>多品項訂單的第二行會被擋下</b>——
 *       同一個 orderNo、同樣是 DEDUCT，只有 SKU 不同。
 *       訂單自 ADR-0007 起就是多品項的，漏掉這一欄等於一般下單只能買一種商品</li>
 * </ul>
 */
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
