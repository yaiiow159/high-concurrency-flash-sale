package com.flashsale.domain.catalog;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * SKU（最小庫存單位）。
 *
 * <p><b>價格掛在 SKU 而非 Product，這是 SPU/SKU 分離的核心。</b>
 * 「iPhone 16 Pro」是 SPU，「iPhone 16 Pro 256G 黑」才是實際被買賣的東西——
 * 256G 與 512G 價格不同、庫存也各自獨立。
 *
 * <p>把價格放在 SPU 上，等於假設一個商品只有一個價格；
 * 那個假設在有規格的商品上立刻破裂，而破裂時要改的是整個資料模型。
 *
 * <p>庫存同理，掛在 SKU（見 ADR-0008），因此本聚合<b>不</b>持有庫存欄位——
 * 庫存是高頻變動的資料，與商品的靜態描述放在一起會讓快取策略無法區分。
 */
public final class Sku {

    private final Long id;
    private final Long productId;
    private final SkuSpec spec;
    private final BigDecimal price;
    private final String barcode;
    private final ProductStatus status;

    private Sku(Long id, Long productId, SkuSpec spec, BigDecimal price,
                String barcode, ProductStatus status) {
        this.id = id;
        // productId 刻意允許為 null——見 create() 的說明。
        // 重建路徑由 restore() 自己要求它不可為 null
        this.productId = productId;
        this.spec = Objects.requireNonNull(spec, "spec 不可為 null");
        this.price = requirePositive(price);
        this.barcode = barcode;
        this.status = Objects.requireNonNull(status, "status 不可為 null");
    }

    /**
     * 建立一個新規格。
     *
     * <p><b>{@code productId} 允許為 {@code null}</b>，而且新建商品時本來就是 null：
     * SKU 是 Product 聚合的一部分，兩者一起被建立，
     * 而商品的 ID 要等持久化之後才存在。要求它非空等於要求
     * 「先存商品、再存規格」——那會讓一個聚合分兩次寫入，
     * 中間出錯就留下一個沒有規格的商品，而那正是我們不允許存在的東西。
     *
     * <p>持久化層本來就不讀這個欄位（SKU 的歸屬由 JPA 的關聯維護），
     * 它只在<b>重建</b>之後才有值——因此 {@link #restore} 仍然要求它非空。
     */
    public static Sku create(Long productId, SkuSpec spec, BigDecimal price, String barcode) {
        return new Sku(null, productId, spec, price, barcode, ProductStatus.DRAFT);
    }

    public static Sku restore(Long id, Long productId, SkuSpec spec, BigDecimal price,
                              String barcode, ProductStatus status) {
        return new Sku(Objects.requireNonNull(id, "重建時 id 不可為 null"),
                Objects.requireNonNull(productId, "重建時 productId 不可為 null"),
                spec, price, barcode, status);
    }

    /**
     * 供訂單行使用的商品快照。
     *
     * <p>訂單存的是這個字串而非 SKU 的引用——商家改名或調價後，
     * 歷史訂單不能跟著變。那是財務問題，不是顯示問題。
     */
    public String snapshotFor(String productName) {
        return "%s（%s）".formatted(productName, spec.display());
    }

    public boolean isPurchasable() {
        return status.isPurchasable();
    }

    private static BigDecimal requirePositive(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "SKU 價格必須大於 0");
        }
        return value;
    }

    public Long id() {
        return id;
    }

    public Long productId() {
        return productId;
    }

    public SkuSpec spec() {
        return spec;
    }

    public BigDecimal price() {
        return price;
    }

    public String barcode() {
        return barcode;
    }

    public ProductStatus status() {
        return status;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Sku other && id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Sku{id=%s, productId=%d, spec=%s, price=%s}".formatted(id, productId, spec.display(), price);
    }
}
