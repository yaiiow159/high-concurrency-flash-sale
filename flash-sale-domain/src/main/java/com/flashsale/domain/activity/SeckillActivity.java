package com.flashsale.domain.activity;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** 秒殺活動聚合根。 */
public final class SeckillActivity {

    private final Long id;
    /** 此活動販售的 SKU。 */
    private final Long skuId;
    /** 商品名稱快照。 */
    private final String productName;
    private final BigDecimal seckillPrice;
    private final int totalStock;
    private final int perUserLimit;
    private final ActivityPeriod period;
    private final ActivityStatus status;
    private final long version;

    private SeckillActivity(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "activityId 不可為 null");
        this.skuId = Objects.requireNonNull(builder.skuId, "skuId 不可為 null");
        this.productName = Objects.requireNonNull(builder.productName, "productName 不可為 null");
        this.seckillPrice = requirePositive(builder.seckillPrice);
        this.totalStock = requireNonNegative(builder.totalStock, "totalStock");
        this.perUserLimit = requirePositiveInt(builder.perUserLimit, "perUserLimit");
        this.period = Objects.requireNonNull(builder.period, "period 不可為 null");
        this.status = Objects.requireNonNull(builder.status, "status 不可為 null");
        this.version = builder.version;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 檢查此活動當下是否可被搶購，不可搶購時直接拋出帶有精確錯誤碼的業務例外。 */
    public void ensurePurchasableAt(Instant now) {
        if (status != ActivityStatus.ONLINE) {
            throw new BusinessException(ErrorCode.ACTIVITY_OFFLINE);
        }
        if (period.notStartedAt(now)) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_STARTED);
        }
        if (period.endedAt(now)) {
            throw new BusinessException(ErrorCode.ACTIVITY_ENDED);
        }
    }

    /** 檢查單次請求數量是否落在限購額度內（跨請求的累計額度由 Redis Lua 腳本把關）。 */
    public void ensureQuantityWithinLimit(int quantity) {
        if (quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "購買數量必須大於 0");
        }
        if (quantity > perUserLimit) {
            throw new BusinessException(ErrorCode.USER_PURCHASE_LIMIT_EXCEEDED,
                    "單次購買數量不可超過限購 " + perUserLimit + " 件");
        }
    }

    /** 上架。 */
    public SeckillActivity publish() {
        return withStatus(ActivityStatus.ONLINE);
    }

    /** 下架。 */
    public SeckillActivity takeOffline() {
        return withStatus(ActivityStatus.OFFLINE);
    }

    private SeckillActivity withStatus(ActivityStatus target) {
        if (status == target) {
            throw new BusinessException(ErrorCode.ILLEGAL_ACTIVITY_STATE_TRANSITION,
                    "活動 %d 已經是 %s".formatted(id, target));
        }
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.ILLEGAL_ACTIVITY_STATE_TRANSITION,
                    "活動 %d 無法從 %s 轉為 %s".formatted(id, status, target));
        }
        return builder()
                .id(id).skuId(skuId).productName(productName).seckillPrice(seckillPrice)
                .totalStock(totalStock).perUserLimit(perUserLimit).period(period)
                .status(target).version(version)
                .build();
    }

    public boolean isPurchasableAt(Instant now) {
        return status == ActivityStatus.ONLINE && period.contains(now);
    }

    /** 計算訂單金額。 */
    public BigDecimal calculateAmount(int quantity) {
        return seckillPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public Long id() {
        return id;
    }

    public Long skuId() {
        return skuId;
    }

    public String productName() {
        return productName;
    }

    public BigDecimal seckillPrice() {
        return seckillPrice;
    }

    public int totalStock() {
        return totalStock;
    }

    public int perUserLimit() {
        return perUserLimit;
    }

    public ActivityPeriod period() {
        return period;
    }

    public ActivityStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    private static BigDecimal requirePositive(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "秒殺價必須大於 0");
        }
        return value;
    }

    private static int requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, field + " 不可為負數");
        }
        return value;
    }

    private static int requirePositiveInt(int value, String field) {
        if (value <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, field + " 必須大於 0");
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SeckillActivity other && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "SeckillActivity{id=%d, product=%s, status=%s, totalStock=%d}"
                .formatted(id, productName, status, totalStock);
    }

    /** 欄位偏多且多為必填，以 Builder 取代長參數建構子，避免呼叫端傳錯順序。 */
    public static final class Builder {
        private Long id;
        private Long skuId;
        private String productName;
        private BigDecimal seckillPrice;
        private int totalStock;
        private int perUserLimit = 1;
        private ActivityPeriod period;
        private ActivityStatus status = ActivityStatus.DRAFT;
        private long version;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder skuId(Long skuId) {
            this.skuId = skuId;
            return this;
        }

        public Builder productName(String productName) {
            this.productName = productName;
            return this;
        }

        public Builder seckillPrice(BigDecimal seckillPrice) {
            this.seckillPrice = seckillPrice;
            return this;
        }

        public Builder totalStock(int totalStock) {
            this.totalStock = totalStock;
            return this;
        }

        public Builder perUserLimit(int perUserLimit) {
            this.perUserLimit = perUserLimit;
            return this;
        }

        public Builder period(ActivityPeriod period) {
            this.period = period;
            return this;
        }

        public Builder period(Instant startAt, Instant endAt) {
            this.period = new ActivityPeriod(startAt, endAt);
            return this;
        }

        public Builder status(ActivityStatus status) {
            this.status = status;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public SeckillActivity build() {
            return new SeckillActivity(this);
        }
    }
}
