package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * 退貨行。
 *
 * <p>{@code skuSnapshot} 與 {@code unitPrice} 是 {@code updatable = false}——
 * 理由與訂單行相同：退款金額由它們算出，事後可改就等於退款金額可改。
 *
 * <p>{@code restockable} 則<b>必須可寫</b>，它是驗收當下才產生的資訊。
 */
@Entity
@Table(name = "return_line")
public class ReturnLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", nullable = false, updatable = false)
    private ReturnRequestEntity request;

    @Column(name = "sku_id", nullable = false, updatable = false)
    private Long skuId;

    @Column(name = "sku_snapshot", nullable = false, length = 255, updatable = false)
    private String skuSnapshot;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal unitPrice;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    /** NULL 代表尚未驗收。 */
    @Column(name = "restockable")
    private Boolean restockable;

    protected ReturnLineEntity() {
        // JPA 專用
    }

    public ReturnLineEntity(Long skuId, String skuSnapshot, BigDecimal unitPrice, int quantity,
                            Boolean restockable) {
        this.skuId = skuId;
        this.skuSnapshot = skuSnapshot;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.restockable = restockable;
    }

    void attachTo(ReturnRequestEntity request) {
        this.request = request;
    }

    void applyInspection(Boolean restockable) {
        this.restockable = restockable;
    }

    public Long getSkuId() {
        return skuId;
    }

    public String getSkuSnapshot() {
        return skuSnapshot;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public Boolean getRestockable() {
        return restockable;
    }
}
