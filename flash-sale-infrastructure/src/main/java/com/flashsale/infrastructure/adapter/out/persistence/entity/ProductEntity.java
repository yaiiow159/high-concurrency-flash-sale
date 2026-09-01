package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 商品（SPU）的持久化模型。
 *
 * <p>SKU 是本聚合的一部分，因此 {@code CascadeType.ALL}：
 * 它們的生命週期完全跟隨商品，也沒有獨立於商品之外的一致性需求。
 */
@Entity
@Table(name = "product", indexes = {
        // 列表查詢：WHERE status = 'ON_SHELF' AND category_id = ?
        @Index(name = "idx_product_status_category", columnList = "status,category_id")
})
public class ProductEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "brand", length = 64)
    private String brand;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<SkuEntity> skus = new ArrayList<>();

    protected ProductEntity() {
    }

    public ProductEntity(Long categoryId, String name, String brand, String description,
                         String status, Instant createdAt) {
        this.categoryId = categoryId;
        this.name = name;
        this.brand = brand;
        this.description = description;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** 加入 SKU 並維護雙向關聯——只設一邊會讓 JPA 寫不出外鍵。 */
    public void addSku(SkuEntity sku) {
        skus.add(sku);
        sku.attachTo(this);
    }

    public void applyChanges(String name, String brand, String description, String status) {
        this.name = name;
        this.brand = brand;
        this.description = description;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getName() {
        return name;
    }

    public String getBrand() {
        return brand;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<SkuEntity> getSkus() {
        return skus;
    }
}
