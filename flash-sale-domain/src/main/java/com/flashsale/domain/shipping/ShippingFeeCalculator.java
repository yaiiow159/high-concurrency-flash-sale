package com.flashsale.domain.shipping;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 運費計算（ADR-0019）。
 *
 * <h2>純函式：不查資料庫、不呼叫遠端</h2>
 *
 * <p>輸入是「重量 + 郵遞區號 + 配送方式 + 費率表」，輸出是一個金額。
 * 與 {@code PricingEngine} 同一個立場——
 * 運費是使用者在結帳頁盯著看的數字，算錯會直接變成客訴，
 * 因此它必須能用單元測試窮舉。
 *
 * <h2>計費重量取「實際重量」與「材積重量」的大者</h2>
 *
 * <p>那是物流業的計價慣例：一箱衛生紙很輕但佔滿整個貨車，
 * 只按重量算會賠死。
 *
 * <p><b>但目前 SKU 只有重量沒有尺寸</b>，因此材積那一半還沒接上。
 * 簽章預留了位置（{@link #chargeableWeight}），
 * 加尺寸欄位時只要補那個方法，計算的其餘部分不必動。
 */
public final class ShippingFeeCalculator {

    /**
     * 材積係數。台灣物流業常見值：材積重量（公斤）= 長×寬×高（公分）/ 6000。
     *
     * <p>先定義下來，即使目前用不到——它是一個業界慣例值，
     * 而不是一個之後要重新研究的參數。
     */
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

    /**
     * 計費重量：實際重量與材積重量取大者。
     *
     * <p><b>目前只回實際重量</b>——SKU 還沒有尺寸欄位。
     * 這個方法存在是為了讓「材積還沒接上」是一件<b>看得見</b>的事：
     * 寫在計算流程裡的 TODO 會被讀到，寫在 issue 裡的不會。
     */
    public static int chargeableWeight(int actualWeightGrams, int volumetricWeightGrams) {
        return Math.max(actualWeightGrams, volumetricWeightGrams);
    }

    /**
     * @param zone           推導出來的區域。回傳它是為了讓畫面能說明
     *                       「為什麼這一單運費比較貴」——一個沒有解釋的離島運費
     *                       只會變成一通客服電話
     * @param appliedTier    實際套用的重量級距上限，供客服核對
     */
    public record Result(BigDecimal fee, ShippingZone zone, int weightGrams, int appliedTier) {
    }
}
