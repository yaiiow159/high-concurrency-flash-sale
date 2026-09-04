package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** 優惠規則。 */
@Entity
@Table(name = "promotion")
public class PromotionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "type", nullable = false, length = 24)
    private String type;

    @Column(name = "rule", nullable = false, length = 24)
    private String rule;

    @Column(name = "threshold", nullable = false, precision = 12, scale = 2)
    private BigDecimal threshold;

    @Column(name = "value", nullable = false, precision = 12, scale = 4)
    private BigDecimal value;

    @Column(name = "max_discount", precision = 12, scale = 2)
    private BigDecimal maxDiscount;

    /** 兌換所需積分；{@code null} 代表不開放兌換。 */
    @Column(name = "point_cost")
    private Long pointCost;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected PromotionEntity() {
        // JPA 專用
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getRule() {
        return rule;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public BigDecimal getValue() {
        return value;
    }

    public BigDecimal getMaxDiscount() {
        return maxDiscount;
    }

    public Long getPointCost() {
        return pointCost;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
