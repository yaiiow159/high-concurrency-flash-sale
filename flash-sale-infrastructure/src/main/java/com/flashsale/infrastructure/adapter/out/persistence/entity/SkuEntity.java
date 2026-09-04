package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * SKU 的持久化模型。
 *
 * <p>價格在這裡而非 ProductEntity —— 這是 SPU/SKU 分離的核心。
 * 庫存則完全不在 Catalog 裡（見 ADR-0008）：它變動極快，
 * 與商品的靜態描述混在一起會讓快取策略無法區分兩者。
 */
@Entity
@Table(name = "sku", indexes = {
        @Index(name = "idx_sku_product", columnList = "product_id"),
        @Index(name = "idx_sku_barcode", columnList = "barcode")
})
public class SkuEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private ProductEntity product;

    /** 規格屬性的 JSON，例如 {"容量":"256G","顏色":"黑"}。保序由寫入端負責。 */
    @Column(name = "spec_json", nullable = false, length = 512)
    private String specJson;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** 單件重量（克），用於運費計費。既有商品由遷移給 1000 克的預設值。 */
    @Column(name = "weight_grams", nullable = false)
    private int weightGrams;

    @Column(name = "barcode", length = 64)
    private String barcode;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    protected SkuEntity() {
    }

    public SkuEntity(String specJson, BigDecimal price, String barcode, String status,
                     int weightGrams) {
        this.specJson = specJson;
        this.price = price;
        this.barcode = barcode;
        this.status = status;
        this.weightGrams = weightGrams;
    }

    void attachTo(ProductEntity product) {
        this.product = product;
    }

    public void applyChanges(String specJson, BigDecimal price, String barcode, String status) {
        this.specJson = specJson;
        this.price = price;
        this.barcode = barcode;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public ProductEntity getProduct() {
        return product;
    }

    public String getSpecJson() {
        return specJson;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getWeightGrams() {
        return weightGrams;
    }

    public String getBarcode() {
        return barcode;
    }

    public String getStatus() {
        return status;
    }
}
