package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 活動的持久化模型。
 *
 * <p>與領域聚合根刻意分開：Entity 為 JPA 服務（無參建構子、可變欄位、延遲載入代理），
 * 聚合根為業務服務（不變條件、私有建構子、行為方法）。
 * 硬把兩者合一，等於讓 ORM 的技術限制決定業務模型該長什麼樣。
 */
@Entity
@Table(name = "seckill_activity")
public class SeckillActivityEntity {

    @Id
    @Column(name = "id")
    private Long id;

    /** 指向 SKU 而非 SPU：庫存與價格都掛在 SKU 上（見 Catalog 脈絡）。 */
    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "product_name", nullable = false, length = 128)
    private String productName;

    @Column(name = "seckill_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal seckillPrice;

    @Column(name = "total_stock", nullable = false)
    private int totalStock;

    @Column(name = "per_user_limit", nullable = false)
    private int perUserLimit;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SeckillActivityEntity() {
        // JPA 專用
    }

    public Long getId() {
        return id;
    }

    public Long getSkuId() {
        return skuId;
    }

    public String getProductName() {
        return productName;
    }

    public BigDecimal getSeckillPrice() {
        return seckillPrice;
    }

    public int getTotalStock() {
        return totalStock;
    }

    public int getPerUserLimit() {
        return perUserLimit;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public String getStatus() {
        return status;
    }

    /**
     * 變更上架狀態。
     *
     * <p>只開放這一個欄位可寫：活動的價格、庫存、時間窗口一旦有人搶過就不能再動，
     * 那會讓已成立的訂單與活動本身對不上。上架狀態是唯一
     * 「改了也不影響既有訂單」的欄位。
     */
    public void applyStatus(String status) {
        this.status = status;
    }

    public long getVersion() {
        return version;
    }
}
