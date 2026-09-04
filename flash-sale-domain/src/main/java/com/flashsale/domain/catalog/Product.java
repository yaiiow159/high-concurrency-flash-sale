package com.flashsale.domain.catalog;

import com.flashsale.domain.catalog.event.ProductIndexChangedEvent;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.DomainEvent;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 商品聚合根（SPU）。
 *
 * <p>SPU 描述「這是什麼商品」，SKU 描述「實際買賣的是哪一個規格」。
 * 價格與庫存都在 SKU 上（見 {@link Sku}）。
 *
 * <p><b>SKU 是本聚合的一部分</b>：它們的生命週期完全跟隨商品，
 * 也沒有獨立於商品之外的一致性需求。因此上下架是聚合層級的操作，
 * 不會出現「商品下架了但某個 SKU 還在賣」這種狀態。
 */
public final class Product {

    private final Long id;
    private final Long categoryId;
    private final String name;
    private final String brand;
    private final String description;
    private final Instant createdAt;

    private ProductStatus status;
    private final List<Sku> skus;

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    private Product(Long id, Long categoryId, String name, String brand, String description,
                    ProductStatus status, List<Sku> skus, Instant createdAt) {
        this.id = id;
        this.categoryId = Objects.requireNonNull(categoryId, "categoryId 不可為 null");
        this.name = requireValidName(name);
        this.brand = brand;
        this.description = description;
        this.status = Objects.requireNonNull(status, "status 不可為 null");
        this.skus = new ArrayList<>(requireAtLeastOneSku(skus));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不可為 null");
    }

    public static Product create(Long categoryId, String name, String brand, String description,
                                 List<Sku> skus, Instant now) {
        return new Product(null, categoryId, name, brand, description,
                ProductStatus.DRAFT, skus, now);
    }

    public static Product restore(Long id, Long categoryId, String name, String brand,
                                  String description, ProductStatus status,
                                  List<Sku> skus, Instant createdAt) {
        return new Product(Objects.requireNonNull(id, "重建時 id 不可為 null"),
                categoryId, name, brand, description, status, skus, createdAt);
    }

    private void registerEvent(DomainEvent event) {
        domainEvents.add(event);
    }

    /** 取出並清空待發布的領域事件；應由應用層在交易內呼叫一次。 */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> pulled = List.copyOf(domainEvents);
        domainEvents.clear();
        return pulled;
    }

    /** 上架。 */
    public void putOnShelf(Instant now) {
        this.status = ProductStatus.ON_SHELF;
        registerEvent(ProductIndexChangedEvent.of(this, now));
    }

    /**
     * 下架。
     *
     * <p>下架不刪除資料——歷史訂單仍需要追溯「這是哪個商品」。
     */
    public void takeOffShelf(Instant now) {
        this.status = ProductStatus.OFF_SHELF;
        registerEvent(ProductIndexChangedEvent.of(this, now));
    }

    /**
     * 確認某個 SKU 當下可購買，不可購買時拋出帶精確錯誤碼的業務例外。
     *
     * <p>採「拋例外」而非回傳布林，讓呼叫端無法忽略失敗原因——
     * 前端需要區分「商品已下架」與「規格不存在」以顯示不同文案。
     */
    public Sku requirePurchasableSku(Long skuId) {
        if (!status.isPurchasable()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_PURCHASABLE,
                    "商品「%s」目前未上架".formatted(name));
        }
        Sku sku = skus.stream()
                .filter(candidate -> Objects.equals(candidate.id(), skuId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SKU_NOT_FOUND));

        if (!sku.isPurchasable()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_PURCHASABLE,
                    "此規格目前未上架");
        }
        return sku;
    }

    /** 最低售價，供列表頁顯示「NT$ 990 起」。 */
    public java.math.BigDecimal lowestPrice() {
        return skus.stream()
                .map(Sku::price)
                .min(java.math.BigDecimal::compareTo)
                .orElseThrow(() -> new IllegalStateException("商品至少要有一個 SKU"));
    }

    private static List<Sku> requireAtLeastOneSku(List<Sku> skus) {
        if (skus == null || skus.isEmpty()) {
            // 沒有 SKU 的商品無法被購買，也無法定價——那不是商品，是一段描述文字
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "商品至少要有一個 SKU");
        }
        return skus;
    }

    private static String requireValidName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "商品名稱不可為空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 128) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "商品名稱不可超過 128 字");
        }
        return trimmed;
    }

    public Long id() {
        return id;
    }

    public Long categoryId() {
        return categoryId;
    }

    public String name() {
        return name;
    }

    public String brand() {
        return brand;
    }

    public String description() {
        return description;
    }

    public ProductStatus status() {
        return status;
    }

    public List<Sku> skus() {
        return List.copyOf(skus);
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Product other && id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Product{id=%s, name=%s, status=%s, skus=%d}".formatted(id, name, status, skus.size());
    }
}
