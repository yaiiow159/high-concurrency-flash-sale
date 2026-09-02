package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 購物車品項的持久化模型。
 *
 * <p><b>沒有購物車表頭。</b>購物車就是某個使用者名下的品項集合，
 * 多一張只有 id 與 user_id 的表，只會多出「使用者存在但購物車列不存在」
 * 這種要處理的中間態。
 *
 * <p><b>不存價格也不存商品名。</b>那些每次顯示時從 Catalog 取——
 * 購物車回答的是「現在買要多少錢」，存快照會在商家調價後變成謊言。
 * 這與訂單行刻意存快照剛好相反，兩者不可互換。
 *
 * <p>{@code (user_id, sku_id)} 唯一：同一個 SKU 在購物車裡只會有一行，
 * 重複加入是累加數量而非新增一行。
 */
@Entity
@Table(name = "cart_item",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_cart_user_sku", columnNames = {"user_id", "sku_id"}),
        indexes = @Index(name = "idx_cart_updated", columnList = "updated_at"))
public class CartItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "sku_id", nullable = false, updatable = false)
    private Long skuId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CartItemEntity() {
    }

    public CartItemEntity(Long userId, Long skuId, int quantity, Instant updatedAt) {
        this.userId = userId;
        this.skuId = skuId;
        this.quantity = quantity;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
