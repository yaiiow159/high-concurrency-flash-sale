package com.flashsale.domain.shipping;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.util.List;

/** 運費計算（ADR-0019）。 */
public final class ShippingFeeCalculator {

    /** 材積係數。台灣物流業常見值：材積重量（公斤）= 長×寬×高（公分）/ 6000。 */
    public static final int VOLUMETRIC_DIVISOR = 6000;

    private ShippingFeeCalculator() {
    }

    /**
     * 算這一趟要收多少運費。
     *
     * @param totalWeightGrams 訂單所有品項的總重量
     * @param postalCode       收貨地址的郵遞區號，用來推導區域
     * @param rates            費率表，由呼叫端從資料庫取出後傳入
     */
    public static Result calculate(int totalWeightGrams, String postalCode,
                                   ShippingMethod method, List<ShippingRate> rates) {
        if (totalWeightGrams <= 0) {
            // 重量為 0 只可能是所有 SKU 都沒填重量。
            // **不能當成免運**——那會讓一個資料缺失變成一筆賠錢的訂單。
            // 用最低級距計費，並讓對帳去發現這些商品
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "訂單總重量必須大於 0（商品可能未設定重量）");
        }

        ShippingZone zone = ShippingZone.fromPostalCode(postalCode);
        ShippingRate rate = ShippingRate.select(rates, method, zone, totalWeightGrams);
        return new Result(rate.fee(), zone, totalWeightGrams, rate.maxWeightGrams());
    }

    /** 計費重量：實際重量與材積重量取大者。 */
    public static int chargeableWeight(int actualWeightGrams, int volumetricWeightGrams) {
        return Math.max(actualWeightGrams, volumetricWeightGrams);
    }

    /**
     * @param zone           推導出來的區域。回傳它是為了讓畫面能說明
     * 「為什麼這一單運費比較貴」——一個沒有解釋的離島運費
     * 只會變成一通客服電話
     * @param appliedTier    實際套用的重量級距上限，供客服核對
     */
    public record Result(BigDecimal fee, ShippingZone zone, int weightGrams, int appliedTier) {
    }
}
