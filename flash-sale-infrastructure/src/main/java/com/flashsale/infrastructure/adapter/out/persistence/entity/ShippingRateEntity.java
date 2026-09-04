package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** 一條運費費率。純讀取——調整費率走遷移，不走應用程式。 */
@Entity
@Table(name = "shipping_rate")
public class ShippingRateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "method", nullable = false, length = 24)
    private String method;

    @Column(name = "zone", nullable = false, length = 24)
    private String zone;

    @Column(name = "max_weight_grams", nullable = false)
    private int maxWeightGrams;

    @Column(name = "fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal fee;

    protected ShippingRateEntity() {
        // JPA 專用
    }

    public String getMethod() {
        return method;
    }

    public String getZone() {
        return zone;
    }

    public int getMaxWeightGrams() {
        return maxWeightGrams;
    }

    public BigDecimal getFee() {
        return fee;
    }
}
