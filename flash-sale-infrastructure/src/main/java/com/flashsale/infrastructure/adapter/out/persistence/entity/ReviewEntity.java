package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 商品評價。
 *
 * <p>{@code orderNo}、{@code skuId}、{@code authorName} 都是
 * {@code updatable = false}：修改評價只能改星等與內容。
 * 作者名稱不可變的理由與訂單行的快照一樣——
 * 那是別人看過並據以決定要不要買的內容。
 */
@Entity
@Table(name = "review")
public class ReviewEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "sku_id", nullable = false, updatable = false)
    private Long skuId;

    @Column(name = "order_no", nullable = false, length = 64, updatable = false)
    private String orderNo;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "author_name", nullable = false, length = 64, updatable = false)
    private String authorName;

    @Column(name = "rating", nullable = false)
    private int rating;

    @Column(name = "content", nullable = false, length = 1000)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReviewEntity() {
        // JPA 專用
    }

    public ReviewEntity(Long productId, Long skuId, String orderNo, Long userId,
                        String authorName, int rating, String content,
                        Instant createdAt, Instant updatedAt) {
        this.productId = productId;
        this.skuId = skuId;
        this.orderNo = orderNo;
        this.userId = userId;
        this.authorName = authorName;
        this.rating = rating;
        this.content = content;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 只開放改星等與內容，其餘欄位在資料庫層也是 updatable = false。 */
    public void applyEdit(int rating, String content, Instant updatedAt) {
        this.rating = rating;
        this.content = content;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public int getRating() {
        return rating;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
