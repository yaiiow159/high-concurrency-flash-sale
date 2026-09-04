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
 * 訂單上的一筆折扣快照。
 *
 * <p>全部欄位 {@code updatable = false}：折扣是成交條件的一部分，
 * 事後可改就等於成交金額可改。與 {@code OrderLineEntity} 的單價同一個道理，
 * 而理由更直接——這幾個數字加起來就是使用者少付的錢。
 */
@Entity
@Table(name = "order_discount")
public class OrderDiscountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private OrderEntity order;

    @Column(name = "source_type", nullable = false, length = 24, updatable = false)
    private String sourceType;

    @Column(name = "source_id", updatable = false)
    private Long sourceId;

    @Column(name = "name", nullable = false, length = 128, updatable = false)
    private String name;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    protected OrderDiscountEntity() {
        // JPA 專用
    }

    public OrderDiscountEntity(String sourceType, Long sourceId, String name, BigDecimal amount) {
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.name = name;
        this.amount = amount;
    }

    void attachTo(OrderEntity order) {
        this.order = order;
    }

    public String getSourceType() {
        return sourceType;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
