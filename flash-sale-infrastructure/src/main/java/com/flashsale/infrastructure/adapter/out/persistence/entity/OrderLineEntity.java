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
 * 訂單行的持久化模型。
 *
 * <p>{@code sku_snapshot} 與 {@code unit_price} 是<b>快照</b>：
 * 商家調價或改商品名之後，歷史訂單不能跟著變。
 * 那是財務問題，不是顯示問題。
 */
@Entity
@Table(name = "order_line",
        indexes = {
                @Index(name = "idx_line_order", columnList = "order_id"),
                // 對帳查詢：某活動被哪些訂單佔用了多少量
                @Index(name = "idx_line_activity", columnList = "source_activity_id")
        })
public class OrderLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private OrderEntity order;

    /** 由 {@code @OrderColumn} 維護，保證行的順序穩定。 */
    @Column(name = "line_no", insertable = false, updatable = false)
    private Integer lineNo;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "sku_snapshot", nullable = false, length = 256)
    private String skuSnapshot;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** 此行來自哪一個秒殺活動；一般下單為 null。 */
    @Column(name = "source_activity_id")
    private Long sourceActivityId;

    protected OrderLineEntity() {
        // JPA 專用
    }

    public OrderLineEntity(Long skuId, String skuSnapshot, BigDecimal unitPrice,
                           int quantity, Long sourceActivityId) {
        this.skuId = skuId;
        this.skuSnapshot = skuSnapshot;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.sourceActivityId = sourceActivityId;
    }

    void attachTo(OrderEntity order) {
        this.order = order;
    }

    public Long getId() {
        return id;
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

    public Long getSourceActivityId() {
        return sourceActivityId;
    }
}
