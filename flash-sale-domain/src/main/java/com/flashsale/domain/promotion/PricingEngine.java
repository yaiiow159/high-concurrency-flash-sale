package com.flashsale.domain.promotion;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 優惠計算引擎（ADR-0013）。
 *
 * <h2>純函式：不查資料庫、不呼叫遠端、不看時鐘</h2>
 *
 * <p>輸入是「品項 + 可用的優惠規則 + 現在時刻」，輸出是折扣明細。
 * 時間由呼叫端傳入而不是自己讀——那是專案第 9 條鐵則，
 * 也讓「這張券在活動最後一秒還能不能用」變成一個可以寫死的測試。
 *
 * <p>優惠規則只會越長越複雜，而複雜的規則<b>只有在能被便宜地驗證時才安全</b>。
 * 把計算留在領域層、不帶任何 I/O，是為了讓
 * 「三件商品套用滿千折百再套八折券是多少」可以用單元測試窮舉。
 *
 * <h2>順序是業務決定，不是實作細節</h2>
 *
 * <p>先打八折再減 100，與先減 100 再打八折，差 20 元。
 * 順序由 {@link DiscountType} 的宣告順序決定，而且<b>只在那裡定義一次</b>。
 */
public final class PricingEngine {

    /** 金額一律算到分。 */
    private static final int SCALE = 2;

    private PricingEngine() {
    }

    /**
     * 計算一組品項可以套用哪些優惠。
     *
     * <p>收 {@link PricedItem} 而不是訂單行，是因為<b>結帳時還沒有訂單</b>——
     * 購物車頁的「套用這張券會折多少」是在下單之前算的。
     *
     * @param items      要計價的品項
     * @param promotions 候選優惠；引擎自己過濾掉不適用的
     * @param now        用於判斷優惠是否在有效期內
     */
    public static PricingResult calculate(List<PricedItem> items,
                                          List<Promotion> promotions,
                                          Instant now) {
        BigDecimal subtotal = sumOf(items);
        if (subtotal.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "訂單金額必須大於 0");
        }

        // 秒殺品項不參與任何優惠（ADR-0013 決策 4）。
        //
        // 判準是 sourceActivityId 而不是 order.channel()——那是「這一行的價格
        // 從哪裡來」，比「這張訂單是誰建的」精確。用 channel 判斷會讓通道差異
        // 滲進共用邏輯，而那是 ADR-0006 明文禁止的。
        boolean hasSeckillItem = items.stream().anyMatch(PricedItem::isFromSeckill);
        if (hasSeckillItem) {
            return PricingResult.noDiscount(subtotal);
        }

        List<AppliedDiscount> applied = new ArrayList<>();
        BigDecimal running = subtotal;

        // 依 DiscountType 的宣告順序逐級套用。每一級都對「上一級之後的金額」計算，
        // 這正是順序會改變結果的原因
        for (DiscountType type : DiscountType.values()) {
            for (Promotion promotion : promotions) {
                if (promotion.type() != type || !promotion.isApplicableAt(now)) {
                    continue;
                }
                BigDecimal discount = promotion.discountFor(running);
                if (discount.signum() <= 0) {
                    continue;
                }
                // 折扣不可超過剩餘金額——否則訂單會變成負數，
                // 而負數金額會一路流進付款與退款
                discount = discount.min(running);
                applied.add(new AppliedDiscount(type, promotion.id(), promotion.name(), discount));
                running = running.subtract(discount);
            }
        }

        return new PricingResult(subtotal, applied, running, allocate(items, subtotal, running));
    }

    /**
     * 把折後總額分攤回每一項。
     *
     * <p>退款要按行退，因此每一項的實付金額必須存在，
     * 而且<b>加總必須等於訂單總額</b>——差一分錢，退完最後一行就對不平。
     *
     * <p>百分比分攤幾乎必然除不盡。規則（ADR-0013 決策 5）：
     * <b>逐行無條件捨去到分，餘數加到金額最大的那一行。</b>
     *
     * <ul>
     *   <li>捨去而非四捨五入：兩個方向都會錯，但「少折給使用者」會被客訴，
     *       「多折」只會被財務發現——選一個會被發現的</li>
     *   <li>餘數給最大行：給最小行時餘數佔該行的比例最大，
     *       退那一行時的誤差最明顯</li>
     * </ul>
     *
     * <p>這條規則的價值在於它<b>被寫下來且被測試釘住</b>，而不在於它是唯一正確的。
     * 沒有明文規則時，每個經手的人都會用自己的直覺，
     * 而那些直覺加總起來就是對不平的帳。
     */
    private static List<BigDecimal> allocate(List<PricedItem> items,
                                             BigDecimal subtotal,
                                             BigDecimal payable) {
        List<BigDecimal> allocated = new ArrayList<>(items.size());
        BigDecimal distributed = BigDecimal.ZERO;

        for (PricedItem item : items) {
            BigDecimal share = item.subtotal()
                    .multiply(payable)
                    .divide(subtotal, SCALE, RoundingMode.DOWN);
            allocated.add(share);
            distributed = distributed.add(share);
        }

        BigDecimal remainder = payable.subtract(distributed);
        if (remainder.signum() != 0) {
            int largest = largestItemIndex(items);
            allocated.set(largest, allocated.get(largest).add(remainder));
        }
        return List.copyOf(allocated);
    }

    private static int largestItemIndex(List<PricedItem> items) {
        int largest = 0;
        for (int i = 1; i < items.size(); i++) {
            if (items.get(i).subtotal().compareTo(items.get(largest).subtotal()) > 0) {
                largest = i;
            }
        }
        return largest;
    }

    private static BigDecimal sumOf(List<PricedItem> items) {
        return items.stream()
                .map(PricedItem::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * 計算結果。
     *
     * @param payable       折後應付
     * @param lineAllocations 每一項分攤到的實付金額，順序與傳入的品項相同。
     *                        <b>加總必然等於 {@code payable}</b>
     */
    public record PricingResult(
            BigDecimal subtotal,
            List<AppliedDiscount> discounts,
            BigDecimal payable,
            List<BigDecimal> lineAllocations
    ) {

        public static PricingResult noDiscount(BigDecimal subtotal) {
            return new PricingResult(subtotal, List.of(), subtotal, List.of());
        }

        public BigDecimal totalDiscount() {
            return subtotal.subtract(payable);
        }

        /** 依折扣金額由大到小，供畫面呈現——使用者最想先看到折最多的那一筆。 */
        public List<AppliedDiscount> discountsByImpact() {
            return discounts.stream()
                    .sorted(Comparator.comparing(AppliedDiscount::amount).reversed())
                    .toList();
        }
    }
}
