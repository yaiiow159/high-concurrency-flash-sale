package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 評分聚合。 */
@Entity
@Table(name = "product_rating")
public class ProductRatingEntity {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "rating_sum", nullable = false)
    private long ratingSum;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount;

    @Column(name = "count_1", nullable = false)
    private int count1;

    @Column(name = "count_2", nullable = false)
    private int count2;

    @Column(name = "count_3", nullable = false)
    private int count3;

    @Column(name = "count_4", nullable = false)
    private int count4;

    @Column(name = "count_5", nullable = false)
    private int count5;

    protected ProductRatingEntity() {
        // JPA 專用
    }

    /** 空的聚合列。第一則評價寫入前必須先有這一列，增量 UPDATE 才有東西可加。 */
    public ProductRatingEntity(Long productId) {
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }

    public long getRatingSum() {
        return ratingSum;
    }

    public int getRatingCount() {
        return ratingCount;
    }

    public int getCount1() {
        return count1;
    }

    public int getCount2() {
        return count2;
    }

    public int getCount3() {
        return count3;
    }

    public int getCount4() {
        return count4;
    }

    public int getCount5() {
        return count5;
    }
}
