package com.flashsale.domain.activity;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 秒殺活動聚合根。
 *
 * <p><b>職責邊界</b>：本聚合只負責「這筆請求在業務規則上允不允許」，
 * <b>不</b>持有即時庫存餘量。餘量是高頻變動的熱點資料，由 Redis 承擔
 * （見 {@code StockRepository}）；聚合內的 {@code totalStock} 僅為活動配置的初始總量，
 * 用於初始化與對帳，不參與扣減判斷。
 *
 * <p>此設計刻意偏離「聚合內強一致」的教科書式建議：秒殺場景下把庫存放進聚合，
 * 等同把每次扣減變成一次資料庫悲觀鎖，吞吐會塌陷。詳見 ADR-0002。
 */
public final class SeckillActivity {

    private final Long id;
    /**
     * 此活動販售的 SKU。
     *
     * <p>指向 SKU 而非 SPU：庫存與價格都掛在 SKU 上（見 Catalog 脈絡），
     * 指向 SPU 的話「賣的是 256G 還是 512G」就無從確定。
     */
    private final Long skuId;
    /**
     * 商品名稱快照。
     *
     * <p>刻意冗餘：熱路徑不能為了顯示商品名去 join Catalog，
     * 而活動一旦開賣，商品改名也不該影響進行中的活動。
     */
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

    /**
     * 檢查此活動當下是否可被搶購，不可搶購時直接拋出帶有精確錯誤碼的業務例外。
     *
     * <p>採「拋例外」而非回傳布林，是為了讓呼叫端無法忽略失敗原因——
     * 前端需要區分「尚未開始」與「已結束」以顯示不同文案。
     *
     * @param now 由呼叫端注入的時間，讓此方法保持可測試（不直接讀系統時鐘）
     */
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
