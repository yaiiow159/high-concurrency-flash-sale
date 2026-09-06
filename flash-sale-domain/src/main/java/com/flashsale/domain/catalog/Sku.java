package com.flashsale.domain.catalog;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.util.Objects;

/** SKU（最小庫存單位）。 */
public final class Sku {

    private final Long id;
    private final Long productId;
    private final SkuSpec spec;
    private final BigDecimal price;
    private final String barcode;
    private final ProductStatus status;

    /** 單件重量（克），用於運費計費。 */
    private final int weightGrams;

    /** 沒有指定重量時的預設值。落在最低運費級距內。 */
    public static final int DEFAULT_WEIGHT_GRAMS = 1000;

    private Sku(Long id, Long productId, SkuSpec spec, BigDecimal price,
                String barcode, ProductStatus status, int weightGrams) {
        this.weightGrams = weightGrams <= 0 ? DEFAULT_WEIGHT_GRAMS : weightGrams;
        this.id = id;
        // productId 刻意允許為 null——見 create() 的說明。
        // 重建路徑由 restore() 自己要求它不可為 null
        this.productId = productId;
        this.spec = Objects.requireNonNull(spec, "spec 不可為 null");
        this.price = requirePositive(price);
        this.barcode = barcode;
        this.status = Objects.requireNonNull(status, "status 不可為 null");
    }

    /** 建立一個新規格。 */
    public static Sku create(Long productId, SkuSpec spec, BigDecimal price, String barcode) {
        return create(productId, spec, price, barcode, DEFAULT_WEIGHT_GRAMS);
    }

    public static Sku create(Long productId, SkuSpec spec, BigDecimal price, String barcode,
                             int weightGrams) {
        return new Sku(null, productId, spec, price, barcode, ProductStatus.DRAFT, weightGrams);
    }

    public static Sku restore(Long id, Long productId, SkuSpec spec, BigDecimal price,
                              String barcode, ProductStatus status) {
        return restore(id, productId, spec, price, barcode, status, DEFAULT_WEIGHT_GRAMS);
    }

    public static Sku restore(Long id, Long productId, SkuSpec spec, BigDecimal price,
                              String barcode, ProductStatus status, int weightGrams) {
        return new Sku(Objects.requireNonNull(id, "重建時 id 不可為 null"),
                Objects.requireNonNull(productId, "重建時 productId 不可為 null"),
                spec, price, barcode, status, weightGrams);
    }

    /** 供訂單行使用的商品快照。 */
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

    public int weightGrams() {
        return weightGrams;
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
