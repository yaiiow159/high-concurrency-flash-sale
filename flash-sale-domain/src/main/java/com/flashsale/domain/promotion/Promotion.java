package com.flashsale.domain.promotion;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/** 一條優惠規則。 */
public final class Promotion {

    private static final int SCALE = 2;

    private final Long id;
    private final String name;
    private final DiscountType type;
    private final PromotionRule rule;

    /** 門檻：訂單金額要達到多少才適用。無門檻為 0。 */
    private final BigDecimal threshold;

    /** 折抵值：固定金額折抵時是金額，比例折扣時是折扣率（0.2 代表打八折折 20%）。 */
    private final BigDecimal value;

    /** 折抵上限。 */
    private final BigDecimal maxDiscount;

    private final Instant startAt;
    private final Instant endAt;
    private final boolean enabled;

    /** 兌換所需積分；{@code null} 代表不開放兌換。 */
    private final Long pointCost;

    private Promotion(Long id, String name, DiscountType type, PromotionRule rule,
                      BigDecimal threshold, BigDecimal value, BigDecimal maxDiscount,
                      Instant startAt, Instant endAt, boolean enabled, Long pointCost) {
        this.pointCost = pointCost;
        this.id = id;
        this.name = requireName(name);
        this.type = Objects.requireNonNull(type, "type 不可為 null");
        this.rule = Objects.requireNonNull(rule, "rule 不可為 null");
        this.threshold = threshold == null ? BigDecimal.ZERO : threshold;
        this.value = requirePositive(value);
        this.maxDiscount = maxDiscount;
        this.startAt = Objects.requireNonNull(startAt, "startAt 不可為 null");
        this.endAt = Objects.requireNonNull(endAt, "endAt 不可為 null");
        this.enabled = enabled;

        if (this.endAt.isBefore(this.startAt)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "優惠的結束時間早於開始時間");
        }
        if (rule == PromotionRule.PERCENTAGE) {
            if (value.compareTo(BigDecimal.ONE) >= 0) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                        "折扣率必須小於 1（0.2 代表折抵 20%）");
            }
            if (maxDiscount == null) {
                // 沒有上限的比例折扣是一顆定時炸彈：一筆十萬元的訂單會折掉兩萬
                throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                        "比例折扣必須設定折抵上限");
            }
        }
    }

    public static Promotion of(Long id, String name, DiscountType type, PromotionRule rule,
                               BigDecimal threshold, BigDecimal value, BigDecimal maxDiscount,
                               Instant startAt, Instant endAt, boolean enabled) {
        return of(id, name, type, rule, threshold, value, maxDiscount,
                startAt, endAt, enabled, null);
    }

    public static Promotion of(Long id, String name, DiscountType type, PromotionRule rule,
                               BigDecimal threshold, BigDecimal value, BigDecimal maxDiscount,
                               Instant startAt, Instant endAt, boolean enabled, Long pointCost) {
        return new Promotion(id, name, type, rule, threshold, value, maxDiscount,
                startAt, endAt, enabled, pointCost);
    }

    /** 現在能不能用。 */
    public boolean isApplicableAt(Instant now) {
        return enabled && !now.isBefore(startAt) && now.isBefore(endAt);
    }

    /** 對指定金額能折抵多少；不適用時回 0。 */
    public BigDecimal discountFor(BigDecimal amount) {
        if (amount.compareTo(threshold) < 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal discount = switch (rule) {
            case FIXED_AMOUNT -> value;
            case PERCENTAGE -> amount.multiply(value).setScale(SCALE, RoundingMode.DOWN);
        };
        if (maxDiscount != null) {
            discount = discount.min(maxDiscount);
        }
        return discount.setScale(SCALE, RoundingMode.DOWN);
    }

    private static String requireName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "優惠名稱不可為空");
        }
        return candidate;
    }

    private static BigDecimal requirePositive(BigDecimal candidate) {
        if (candidate == null || candidate.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "折抵值必須大於 0");
        }
        return candidate;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public DiscountType type() {
        return type;
    }

    public PromotionRule rule() {
        return rule;
    }

    public BigDecimal threshold() {
        return threshold;
    }

    public BigDecimal value() {
        return value;
    }

    public BigDecimal maxDiscount() {
        return maxDiscount;
    }

    public Instant startAt() {
        return startAt;
    }

    public Instant endAt() {
        return endAt;
    }

    public boolean enabled() {
        return enabled;
    }

    public Long pointCost() {
        return pointCost;
    }

    /** 這個優惠能不能用積分換。 */
    public boolean isExchangeable() {
        return pointCost != null && pointCost > 0;
    }
}
