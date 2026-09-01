package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** 類目的持久化模型。 */
@Entity
@Table(name = "category", indexes = @Index(name = "idx_category_parent", columnList = "parent_id"))
public class CategoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** NULL 表示根類目。 */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** 存下來而非遞迴計算——類目樹極少變動卻在每次商品查詢時被讀取。 */
    @Column(name = "level", nullable = false)
    private int level;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected CategoryEntity() {
    }

    public Long getId() {
        return id;
    }

    public Long getParentId() {
        return parentId;
    }

    public String getName() {
        return name;
    }

    public int getLevel() {
        return level;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
