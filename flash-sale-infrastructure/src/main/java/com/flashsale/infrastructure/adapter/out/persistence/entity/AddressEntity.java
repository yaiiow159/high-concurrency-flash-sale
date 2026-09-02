package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 收貨地址簿條目的持久化模型。
 *
 * <p>與 {@code OrderEntity} 的收貨欄位<b>刻意重複</b>，而不是讓訂單以外鍵指過來。
 * 那份重複正是快照的全部意義：地址簿會變，訂單不能跟著變。
 *
 * <p>沒有 {@code UNIQUE(user_id, is_default)}：那個索引會連
 * 「同一個使用者有多筆非預設地址」都一起擋掉。MySQL 沒有部分唯一索引，
 * 因此「每人最多一筆預設」只能由應用層在交易內維持。
 */
@Entity
@Table(name = "address", indexes = {
        @Index(name = "idx_address_user", columnList = "user_id, is_default")
})
public class AddressEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "recipient_name", nullable = false, length = 32)
    private String recipientName;

    @Column(name = "phone", nullable = false, length = 24)
    private String phone;

    @Column(name = "postal_code", nullable = false, length = 8)
    private String postalCode;

    @Column(name = "region", nullable = false, length = 32)
    private String region;

    @Column(name = "district", nullable = false, length = 32)
    private String district;

    @Column(name = "street_address", nullable = false, length = 128)
    private String streetAddress;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AddressEntity() {
    }

    public AddressEntity(Long userId, String recipientName, String phone, String postalCode,
                         String region, String district, String streetAddress,
                         boolean defaultAddress, Instant createdAt) {
        this.userId = userId;
        this.recipientName = recipientName;
        this.phone = phone;
        this.postalCode = postalCode;
        this.region = region;
        this.district = district;
        this.streetAddress = streetAddress;
        this.defaultAddress = defaultAddress;
        this.createdAt = createdAt;
    }

    public void applyChanges(String recipientName, String phone, String postalCode,
                             String region, String district, String streetAddress,
                             boolean defaultAddress) {
        this.recipientName = recipientName;
        this.phone = phone;
        this.postalCode = postalCode;
        this.region = region;
        this.district = district;
        this.streetAddress = streetAddress;
        this.defaultAddress = defaultAddress;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getPhone() {
        return phone;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getRegion() {
        return region;
    }

    public String getDistrict() {
        return district;
    }

    public String getStreetAddress() {
        return streetAddress;
    }

    public boolean isDefaultAddress() {
        return defaultAddress;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
