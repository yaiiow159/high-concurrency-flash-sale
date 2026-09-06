package com.flashsale.domain.shipping;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 一條運費費率。 */
public record ShippingRate(
        ShippingMethod method,
        ShippingZone zone,
        int maxWeightGrams,
        BigDecimal fee
) {

    public ShippingRate {
        Objects.requireNonNull(method, "method 不可為 null");
        Objects.requireNonNull(zone, "zone 不可為 null");
        if (maxWeightGrams <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "重量上限必須大於 0");
        }
        if (fee == null || fee.signum() < 0) {
            // 費用可以是 0（免運級距），但不能是負數——那不是運費，是送錢
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "運費不可為負數");
        }
    }

    /** 從費率表挑出適用的那一筆。 */
    public static ShippingRate select(List<ShippingRate> rates, ShippingMethod method,
                                      ShippingZone zone, int weightGrams) {
        return rates.stream()
                .filter(rate -> rate.method == method && rate.zone == zone)
                .filter(rate -> rate.maxWeightGrams >= weightGrams)
                .min(Comparator.comparingInt(ShippingRate::maxWeightGrams))
                .orElseThrow(() -> new BusinessException(ErrorCode.SHIPPING_RATE_NOT_FOUND,
                        "找不到適用的運費級距（%s／%s／%d 克）"
                                .formatted(method.displayName(), zone.displayName(), weightGrams)));
    }
}
