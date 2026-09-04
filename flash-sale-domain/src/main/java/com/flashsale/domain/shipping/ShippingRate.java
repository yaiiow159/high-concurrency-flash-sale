package com.flashsale.domain.shipping;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 一條運費費率。
 *
 * <h2>費率放資料庫，不放程式碼</h2>
 *
 * <p>運費是<b>營運會調的東西</b>（換物流商、油價、促銷檔期），
 * 而每次調整都要改程式碼並重新部署是不合理的。
 *
 * <p>維度是 {@code (配送方式, 區域, 重量上限) → 費用}。
 *
 * @param maxWeightGrams 這一級的重量上限（含）。查表時取
 *                       「上限 >= 訂單重量」裡最小的那一筆
 */
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

    /**
     * 從費率表挑出適用的那一筆。
     *
     * <p>純函式：費率表由呼叫端傳入，這裡不查資料庫。
     * 這讓「5 公斤的離島訂單運費是多少」可以用單元測試窮舉，
     * 而那正是運費最容易算錯、也最容易被客訴的地方。
     *
     * <p>取「上限 >= 重量」裡<b>最小</b>的那一筆。用最小而不是第一筆，
     * 是因為費率表的順序不該影響結果——排序錯了就多收錢，
     * 而那種錯誤不會拋任何例外。
     *
     * @throws BusinessException 沒有任何級距涵蓋這個重量。
     *                           這代表費率表有缺口（例如超重），
     *                           而<b>猜一個數字比報錯危險</b>：
     *                           猜低了商家賠運費，猜高了使用者被多收
     */
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
